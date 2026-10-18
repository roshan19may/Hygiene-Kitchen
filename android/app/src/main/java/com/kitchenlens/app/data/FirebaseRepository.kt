package com.kitchenlens.app.data

import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.kitchenlens.app.util.HygieneScore
import com.kitchenlens.app.util.QrLabel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.UUID

class FirebaseRepository : KitchenRepository {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()

    override val isDemo = false

    override fun signedInUserId(): String? = auth.currentUser?.uid

    override suspend fun signIn(email: String, password: String) {
        auth.signInWithEmailAndPassword(email.trim(), password).await()
    }

    override suspend fun signUp(email: String, password: String) {
        auth.createUserWithEmailAndPassword(email.trim(), password).await()
    }

    override fun signOut() = auth.signOut()

    override suspend fun loadKitchenId(): String? {
        val uid = auth.currentUser?.uid ?: return null
        return db.collection("users").document(uid).get().await().getString("kitchenId")
    }

    override suspend fun createKitchen(name: String, locality: String, city: String, fssai: String, tier: String): String {
        val uid = auth.currentUser?.uid ?: error("Not signed in")
        val ref = db.collection("kitchens").document()
        db.batch()
            .set(ref, mapOf(
                "name" to name.trim(), "locality" to locality.trim(), "city" to city,
                "fssai" to fssai.trim(), "tier" to tier, "ownerUid" to uid,
                "latest" to emptyMap<String, Any>(), "recentHashes" to emptyList<String>(),
                "createdAt" to FieldValue.serverTimestamp(),
            ))
            .set(db.collection("users").document(uid), mapOf(
                "kitchenId" to ref.id, "email" to (auth.currentUser?.email ?: ""),
            ))
            .commit().await()
        return ref.id
    }

    private fun parse(snap: DocumentSnapshot): Kitchen = kitchenFromMap(snap.id, snap.data ?: emptyMap())

    override fun observeKitchen(kitchenId: String): Flow<Kitchen?> = callbackFlow {
        val reg = db.collection("kitchens").document(kitchenId).addSnapshotListener { snap, _ ->
            if (snap != null && snap.exists()) trySend(parse(snap))
        }
        awaitClose { reg.remove() }
    }

    override suspend fun getKitchen(kitchenId: String): Kitchen =
        parse(db.collection("kitchens").document(kitchenId).get().await())

    private suspend fun upload(file: File, path: String, contentType: String, onProgress: (Float) -> Unit): String {
        val ref = storage.reference.child(path)
        val meta = StorageMetadata.Builder().setContentType(contentType).build()
        ref.putFile(Uri.fromFile(file), meta)
            .addOnProgressListener { s ->
                if (s.totalByteCount > 0) onProgress(s.bytesTransferred.toFloat() / s.totalByteCount)
            }
            .await()
        return ref.downloadUrl.await().toString()
    }

    override suspend fun saveLog(
        kitchen: Kitchen, section: Section, key: String, staffName: String, media: File,
        mediaType: MediaType, billPhoto: File?, details: Map<String, String>, label: String,
        photoHash: String?, onProgress: (Float) -> Unit,
    ) {
        val id = UUID.randomUUID().toString()
        val isVideo = mediaType == MediaType.VIDEO
        val mediaUrl = upload(
            media, "kitchens/${kitchen.id}/logs/$id.${if (isVideo) "mp4" else "jpg"}",
            if (isVideo) "video/mp4" else "image/jpeg", onProgress,
        )
        val billUrl = billPhoto?.let { upload(it, "kitchens/${kitchen.id}/logs/${id}_bill.jpg", "image/jpeg") {} }
        val allDetails = if (billUrl != null) details + ("billUrl" to billUrl) else details
        val now = System.currentTimeMillis()

        val kitchenRef = db.collection("kitchens").document(kitchen.id)
        val updates = mutableMapOf<String, Any>(
            "latest.$key" to publicSummary(now, mediaUrl, mediaType, label, allDetails),
        )
        if (photoHash != null) updates["recentHashes"] = (listOf(photoHash) + kitchen.recentHashes).take(60)

        db.batch()
            .set(kitchenRef.collection("logs").document(id), mapOf(
                "section" to section.name, "key" to key, "staffName" to staffName.trim(),
                "mediaType" to mediaType.value, "mediaUrl" to mediaUrl, "details" to allDetails,
                "label" to label, "capturedAtMillis" to now, "createdAt" to FieldValue.serverTimestamp(),
            ))
            .update(kitchenRef, updates)
            .commit().await()
    }

    override suspend fun createOrder(
        kitchen: Kitchen, platform: String, orderRef: String, packerName: String,
        foodType: String, tempC: Double, clip: File, onProgress: (Float) -> Unit,
    ): OrderSummary {
        var code = QrLabel.newCode()
        repeat(3) { if (db.collection("orders").document(code).get().await().exists()) code = QrLabel.newCode() }

        val clipUrl = upload(clip, "kitchens/${kitchen.id}/orders/$code.mp4", "video/mp4", onProgress)
        val fresh = getKitchen(kitchen.id) // latest hygiene state at the moment of packing
        val now = System.currentTimeMillis()
        val tempOk = HygieneScore.temperatureOk(foodType, tempC)
        val score = HygieneScore.compute(fresh.latest, tempOk, now)
        val record = buildOrderRecord(fresh, platform, orderRef, packerName, foodType, tempC, tempOk, clipUrl, now, score)

        db.batch()
            .set(db.collection("orders").document(code), record + ("createdAt" to FieldValue.serverTimestamp()))
            // Private kitchen record keeps the full platform order number
            .set(db.collection("kitchens").document(kitchen.id).collection("logs").document(), mapOf(
                "section" to Section.PACKING.name, "key" to Section.PACKING.name,
                "staffName" to packerName.trim(), "mediaType" to "video", "mediaUrl" to clipUrl,
                "details" to mapOf("orderRef" to orderRef, "platform" to platform, "code" to code,
                    "tempC" to tempC.toString(), "foodType" to foodType),
                "label" to "Order packed", "capturedAtMillis" to now,
                "createdAt" to FieldValue.serverTimestamp(),
            ))
            .commit().await()
        return orderSummaryFromMap(code, record)
    }

    override suspend fun recentOrders(kitchenId: String): List<OrderSummary> =
        db.collection("orders")
            .whereEqualTo("kitchenId", kitchenId)
            .orderBy("packedAtMillis", Query.Direction.DESCENDING)
            .limit(50)
            .get().await()
            .documents.map { orderSummaryFromMap(it.id, it.data ?: emptyMap()) }

    override suspend fun getOrder(code: String): Map<String, Any?>? =
        db.collection("orders").document(code).get().await().data
}
