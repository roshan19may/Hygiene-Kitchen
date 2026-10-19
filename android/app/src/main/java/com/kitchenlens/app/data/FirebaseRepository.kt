package com.kitchenlens.app.data

import android.net.Uri
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.kitchenlens.app.util.HygieneScore
import com.kitchenlens.app.util.ImageTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import com.kitchenlens.app.util.KitchenStats
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

    override suspend fun createKitchen(name: String, locality: String, city: String, fssai: String, gstin: String, tier: String): String {
        val uid = auth.currentUser?.uid ?: error("Not signed in")
        val ref = db.collection("kitchens").document()
        db.batch()
            .set(ref, profileMap(name, locality, city, fssai, gstin, tier) + mapOf(
                "ownerUid" to uid,
                "latest" to emptyMap<String, Any>(), "recentHashes" to emptyList<String>(),
                "createdAt" to FieldValue.serverTimestamp(),
            ))
            .set(db.collection("users").document(uid), mapOf(
                "kitchenId" to ref.id, "email" to (auth.currentUser?.email ?: ""),
            ))
            .commit().await()
        return ref.id
    }

    override suspend fun updateKitchenProfile(kitchenId: String, profile: Map<String, String>) {
        db.collection("kitchens").document(kitchenId).update(HashMap<String, Any>(profile)).await()
    }

    override suspend fun replaceCurrentRecords(kitchenId: String, latest: Map<String, Map<String, Any?>>) {
        db.collection("kitchens").document(kitchenId).update("latest", latest).await()
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

    // ---------- Fast uploads ----------
    // 1. Uploads start in the background the moment a photo or clip is captured (prefetch),
    //    so Save usually finds them already done.
    // 2. Several files upload at the same time instead of one after another.
    // 3. Every photo also gets a ~20 KB thumbnail, so reports show previews instantly.
    // 4. Files are marked cacheable, so a customer's phone downloads each one only once.

    private data class Uploaded(val url: String, val thumb: String)

    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, Deferred<Uploaded>>()
    private val slots = Semaphore(4)

    override fun prefetch(kitchen: Kitchen, file: File, video: Boolean) {
        val key = file.absolutePath
        if (pending.containsKey(key)) return
        pending[key] = background.async { send(kitchen.id, file, video) {} }
    }

    /** Uses the background upload if one was started (and worked), otherwise uploads now. */
    private suspend fun uploadMedia(kitchenId: String, file: File, video: Boolean, onProgress: (Float) -> Unit = {}): Uploaded {
        pending.remove(file.absolutePath)?.let { started ->
            val done = runCatching { started.await() }.getOrNull()
            if (done != null) { onProgress(1f); return done }
        }
        return send(kitchenId, file, video, onProgress)
    }

    private suspend fun send(kitchenId: String, file: File, video: Boolean, onProgress: (Float) -> Unit): Uploaded =
        slots.withPermit {
            val id = UUID.randomUUID().toString()
            val base = "kitchens/$kitchenId/logs/$id"
            if (video) return@withPermit Uploaded(upload(file, "$base.mp4", "video/mp4", onProgress), "")
            coroutineScope {
                val thumb = async {
                    runCatching {
                        val t = ImageTools.thumbnail(file, file.absoluteFile.parentFile ?: file.absoluteFile)
                        upload(t, "${base}_t.jpg", "image/jpeg") {}.also { t.delete() }
                    }.getOrDefault("")
                }
                Uploaded(upload(file, "$base.jpg", "image/jpeg", onProgress), thumb.await())
            }
        }

    private suspend fun upload(file: File, path: String, contentType: String, onProgress: (Float) -> Unit): String {
        val ref = storage.reference.child(path)
        val meta = StorageMetadata.Builder().setContentType(contentType)
            // Files never change once uploaded, so browsers may keep them for a year
            .setCacheControl("public, max-age=31536000, immutable")
            .build()
        ref.putFile(Uri.fromFile(file), meta)
            .addOnProgressListener { s ->
                if (s.totalByteCount > 0) onProgress(s.bytesTransferred.toFloat() / s.totalByteCount)
            }
            .await()
        return ref.downloadUrl.await().toString()
    }

    override suspend fun saveLog(
        kitchen: Kitchen, section: Section, key: String, staffName: String, media: File,
        mediaType: MediaType, details: Map<String, String>, label: String,
        photoHash: String?, onProgress: (Float) -> Unit,
    ) {
        val id = UUID.randomUUID().toString()
        val isVideo = mediaType == MediaType.VIDEO
        val uploaded = uploadMedia(kitchen.id, media, isVideo, onProgress)
        val mediaUrl = uploaded.url
        val now = System.currentTimeMillis()

        val kitchenRef = db.collection("kitchens").document(kitchen.id)
        val updates = mutableMapOf<String, Any>()
        if (section == Section.SPOT) {
            // Spot checks count towards the kitchen rating; they don't replace a hygiene record
            updates["spotAnswers"] = (kitchen.spotAnswers + now).takeLast(60)
        } else {
            updates["latest.$key"] = publicSummary(now, mediaUrl, mediaType, label,
                details + mapOf("recordedBy" to staffName.trim().substringBefore(" ")), mediaThumbs = listOf(uploaded.thumb))
        }
        if (photoHash != null) updates["recentHashes"] = (listOf(photoHash) + kitchen.recentHashes).take(60)

        db.batch()
            .set(kitchenRef.collection("logs").document(id), mapOf(
                "section" to section.name, "key" to key, "staffName" to staffName.trim(),
                "mediaType" to mediaType.value, "mediaUrl" to mediaUrl, "details" to details,
                "label" to label, "capturedAtMillis" to now, "createdAt" to FieldValue.serverTimestamp(),
            ))
            .update(kitchenRef, updates)
            .commit().await()
    }

    override suspend fun saveInventory(
        kitchen: Kitchen, staffName: String, supplierType: String?,
        photos: Map<String, List<InventoryPhoto>>, bills: List<InventoryBill>,
        onProgress: (Float) -> Unit,
    ) {
        val total = photos.values.sumOf { it.size } + bills.size
        val done = AtomicInteger(0)
        fun step() { onProgress(done.incrementAndGet().toFloat() / total) }

        // All photos and bills upload at the same time (up to 4 at once)
        val flat = photos.flatMap { (cat, list) -> list.map { cat to it } }
        val (photoResults, billResults) = coroutineScope {
            val p = flat.map { (cat, ph) -> async { cat to uploadMedia(kitchen.id, ph.file, false).also { step() } } }
            val b = bills.map { bill -> async { uploadMedia(kitchen.id, bill.file, false).also { step() } } }
            p.awaitAll() to b.awaitAll()
        }
        val photoUrls = photoResults.groupBy({ it.first }, { it.second.url })
        val photoThumbs = photoResults.groupBy({ it.first }, { it.second.thumb })
        val billUrls = billResults.map { it.url }

        val records = buildInventoryRecords(photoUrls, bills, billUrls, supplierType)
        val now = System.currentTimeMillis()
        val kitchenRef = db.collection("kitchens").document(kitchen.id)
        val updates = mutableMapOf<String, Any>()
        val batch = db.batch()
        records.forEach { r ->
            updates["latest.${r.key}"] = publicSummary(now, r.mediaUrls.first(), MediaType.PHOTO, r.label,
                r.details + mapOf("recordedBy" to staffName.trim().substringBefore(" ")), r.mediaUrls,
                photoThumbs[r.category].orEmpty())
            batch.set(kitchenRef.collection("logs").document(), mapOf(
                "section" to Section.INVENTORY.name, "key" to r.key, "staffName" to staffName.trim(),
                "mediaType" to MediaType.PHOTO.value, "mediaUrl" to r.mediaUrls.first(), "mediaUrls" to r.mediaUrls,
                "details" to r.details, "label" to r.label, "capturedAtMillis" to now,
                "createdAt" to FieldValue.serverTimestamp(),
            ))
        }
        val newHashes = photos.values.flatten().map { it.hash }
        updates["recentHashes"] = (newHashes + kitchen.recentHashes).take(60)
        batch.update(kitchenRef, updates)
        batch.commit().await()
    }

    override suspend fun createOrder(
        kitchen: Kitchen, platform: String, orderRef: String, packerName: String,
        foodType: String, tempC: Double, clip: File, clipRecordedAt: Long, onProgress: (Float) -> Unit,
    ): OrderSummary {
        val refKey = orderRefKey(platform, orderRef)
        findOrderCode(platform, orderRef)?.let { throw DuplicateOrderException(it, platform, orderRef) }
        var code = QrLabel.newCode()
        repeat(3) { if (db.collection("orders").document(code).get().await().exists()) code = QrLabel.newCode() }

        // Usually already uploaded in the background while the packer filled in the form
        val clipUrl = pending.remove(clip.absolutePath)?.let { started -> runCatching { started.await().url }.getOrNull() }
            ?.also { onProgress(1f) }
            ?: upload(clip, "kitchens/${kitchen.id}/orders/$code.mp4", "video/mp4", onProgress)
        val fresh = getKitchen(kitchen.id) // latest hygiene state at the moment of packing
        val now = System.currentTimeMillis()
        val tempOk = HygieneScore.temperatureOk(foodType, tempC)
        val scoreResult = HygieneScore.computeWeighted(fresh.latest, foodType, tempC, packerName, clipUrl, now, fresh.notStocked)
        val score = scoreResult.percent
        val stats = runCatching { kitchenStats(fresh, now) }.getOrNull()
        val record = buildOrderRecord(fresh, platform, orderRef, packerName, foodType, tempC, tempOk, clipUrl, now, score,
            clipRecordedAt, scoreResult.toMap(), stats?.toMap() ?: emptyMap())
        val orderStats = KitchenStats.append(fresh.orderStats, KitchenStats.encode(now, score, scoreResult.status), now)

        db.batch()
            .set(db.collection("orders").document(code), record + ("createdAt" to FieldValue.serverTimestamp()))
            // Public index: platform + order number -> seal code (customers can look up by order number)
            .set(db.collection("orderRefs").document(refKey), mapOf(
                "code" to code, "kitchenId" to kitchen.id, "createdAt" to FieldValue.serverTimestamp(),
            ))
            // Private kitchen record keeps the full platform order number
            .set(db.collection("kitchens").document(kitchen.id).collection("logs").document(), mapOf(
                "section" to Section.PACKING.name, "key" to Section.PACKING.name,
                "staffName" to packerName.trim(), "mediaType" to "video", "mediaUrl" to clipUrl,
                "details" to mapOf("orderRef" to orderRef, "platform" to platform, "code" to code,
                    "tempC" to tempC.toString(), "foodType" to foodType),
                "label" to "Order packed", "capturedAtMillis" to now,
                "createdAt" to FieldValue.serverTimestamp(),
            ))
            // Rolling 30-day list used for the kitchen rating (no extra reads needed)
            .update(db.collection("kitchens").document(kitchen.id), "orderStats", orderStats)
            .commit().await()
        return orderSummaryFromMap(code, record)
    }

    override suspend fun findOrderCode(platform: String, orderRef: String): String? {
        if (orderRef.isBlank()) return null
        val snap = db.collection("orderRefs").document(orderRefKey(platform, orderRef)).get().await()
        return if (snap.exists()) snap.getString("code") else null
    }

    override suspend fun recentOrders(kitchenId: String): List<OrderSummary> =
        db.collection("orders")
            .whereEqualTo("kitchenId", kitchenId)
            .orderBy("packedAtMillis", Query.Direction.DESCENDING)
            .limit(50)
            .get().await()
            .documents.map { orderSummaryFromMap(it.id, it.data ?: emptyMap()) }

    override suspend fun deleteAccount(password: String) {
        val user = auth.currentUser ?: error("Not signed in")
        val email = user.email ?: error("This account has no email address")
        // Confirm the password first, so a half-finished deletion cannot happen
        user.reauthenticate(EmailAuthProvider.getCredential(email, password)).await()

        val uid = user.uid
        val kid = db.collection("users").document(uid).get().await().getString("kitchenId")
        if (kid != null) {
            val kitchenRef = db.collection("kitchens").document(kid)
            // Hygiene records
            deleteInBatches(kitchenRef.collection("logs").get().await().documents.map { it.reference })
            // Orders and their order-number links
            val orders = db.collection("orders").whereEqualTo("kitchenId", kid).get().await().documents
            val refs = orders.mapNotNull { (it.getString("orderRefKey"))?.let { k -> db.collection("orderRefs").document(k) } }
            deleteInBatches(refs + orders.map { it.reference })
            // Customer feedback and the public kitchen page
            deleteInBatches(db.collection("feedback").whereEqualTo("kitchenId", kid).get().await().documents.map { it.reference })
            runCatching { db.collection("publicKitchens").document(kid).delete().await() }
            // Photos and clips
            deleteStorageFolder(storage.reference.child("kitchens/$kid"))
            kitchenRef.delete().await()
        }
        db.collection("users").document(uid).delete().await()
        user.delete().await()
    }

    private suspend fun deleteInBatches(refs: List<com.google.firebase.firestore.DocumentReference>) {
        refs.chunked(400).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { batch.delete(it) }
            batch.commit().await()
        }
    }

    private suspend fun deleteStorageFolder(folder: com.google.firebase.storage.StorageReference) {
        val list = folder.listAll().await()
        list.items.forEach { runCatching { it.delete().await() } }
        list.prefixes.forEach { deleteStorageFolder(it) }
    }

    override suspend fun recentFeedback(kitchenId: String, since: Long): List<Pair<Boolean, Boolean>> =
        db.collection("feedback")
            .whereEqualTo("kitchenId", kitchenId)
            .whereGreaterThanOrEqualTo("at", since)
            .limit(1000)
            .get().await()
            .documents.map { (it.getBoolean("sealIntact") ?: false) to (it.getBoolean("foodHot") ?: false) }

    override suspend fun publishKitchen(kitchen: Kitchen, stats: KitchenStats) {
        // merge: never touches "verifiedVisit", which only KitchenLens staff can set
        db.collection("publicKitchens").document(kitchen.id).set(publicKitchenMap(kitchen, stats), SetOptions.merge()).await()
    }

    override suspend fun getOrder(code: String): Map<String, Any?>? =
        db.collection("orders").document(code).get().await().data
}
