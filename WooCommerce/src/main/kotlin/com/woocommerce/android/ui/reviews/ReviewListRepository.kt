package com.woocommerce.android.ui.reviews

import com.woocommerce.android.model.ProductReview
import com.woocommerce.android.model.ProductReviewProduct
import com.woocommerce.android.model.toAppModel
import com.woocommerce.android.tools.SelectedSite
import com.woocommerce.android.util.WooLog
import com.woocommerce.android.util.WooLog.T.REVIEWS
import com.woocommerce.android.util.suspendCoroutineWithTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode.MAIN
import org.wordpress.android.fluxc.Dispatcher
import org.wordpress.android.fluxc.action.WCProductAction.FETCH_PRODUCTS
import org.wordpress.android.fluxc.action.WCProductAction.FETCH_PRODUCT_REVIEWS
import org.wordpress.android.fluxc.generated.WCProductActionBuilder
import org.wordpress.android.fluxc.store.WCProductStore
import org.wordpress.android.fluxc.store.WCProductStore.FetchProductsPayload
import org.wordpress.android.fluxc.store.WCProductStore.OnProductChanged
import org.wordpress.android.fluxc.store.WCProductStore.OnProductReviewChanged
import javax.inject.Inject
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

class ReviewListRepository @Inject constructor(
    private val dispatcher: Dispatcher,
    private val productStore: WCProductStore,
    private val selectedSite: SelectedSite
) {
    companion object {
        private const val ACTION_TIMEOUT = 10L * 1000
        private const val PAGE_SIZE = WCProductStore.NUM_REVIEWS_PER_FETCH
    }

    private var continuationReview: Continuation<Boolean>? = null
    private var continuationProduct: Continuation<Boolean>? = null
    private var offset = 0
    private var isFetchingProductReviews = false
    var canLoadMoreReviews = false
        private set

    init {
        dispatcher.register(this)
    }

    fun onCleanup() {
        dispatcher.unregister(this)
    }

    /**
     * Fetch product reviews from the API, wait for it to complete, and then query the db
     * for the fetched reviews.
     *
     * @param [loadMore] if true, creates an offset to fetch the next page of [ProductReview]s
     * from the API.
     * @return List of [ProductReview]
     */
    suspend fun fetchAndLoadProductReviews(loadMore: Boolean = false): List<ProductReview> {
        if (!isFetchingProductReviews) {
            suspendCoroutineWithTimeout<Boolean>(ACTION_TIMEOUT) {
                offset = if (loadMore) offset + PAGE_SIZE else 0
                isFetchingProductReviews = true
                continuationReview = it

                val payload = WCProductStore.FetchProductReviewsPayload(selectedSite.get(), offset)
                dispatcher.dispatch(WCProductActionBuilder.newFetchProductReviewsAction(payload))
            }

            /*
             * Fetch any products associated with these reviews missing from the db
             */
            getProductReviews().map { it.remoteProductId }.distinct().takeIf { it.isNotEmpty() }?.let {
                fetchProductsByRemoteId(it)
            }
        }

        return getCachedProductReviews()
    }

    /**
     * Create a distinct list of products associated with the reviews already in the db, then
     * pass that list to get a map of those products from the db. Only reviews that have an existing
     * cached product will be returned.
     */
    suspend fun getCachedProductReviews(): List<ProductReview> {
        var cachedReviews = getProductReviews()
        if (cachedReviews.isNotEmpty()) {
            val relatedProducts = cachedReviews.map { it.remoteProductId }.distinct()
            val productsMap = getProductsByRemoteIdMap(relatedProducts)
            cachedReviews = cachedReviews.filter {
                // Only returns reviews that have a matching product in the db.
                productsMap.containsKey(it.remoteProductId) && productsMap[it.remoteProductId] != null
            }.also { review ->
                review.forEach { it.product = productsMap[it.remoteProductId] }
            }
        }
        return cachedReviews
    }

    /**
     * Fetch products from the API and suspends until finished.
     */
    private suspend fun fetchProductsByRemoteId(remoteProductIds: List<Long>) {
        suspendCoroutineWithTimeout<Boolean>(ACTION_TIMEOUT) {
            continuationProduct = it

            val payload = FetchProductsPayload(selectedSite.get(), remoteProductIds = remoteProductIds)
            dispatcher.dispatch(WCProductActionBuilder.newFetchProductsAction(payload))
        }
    }

    /**
     * Returns a list of all [ProductReview]s for the active site.
     */
    private suspend fun getProductReviews(): List<ProductReview> {
        return withContext(Dispatchers.IO) {
            productStore.getProductReviewsForSite(selectedSite.get()).map { it.toAppModel() }
        }
    }

    /**
     * Queries the db for a [org.wordpress.android.fluxc.model.WCProductModel] matching the
     * provided [remoteProductId] and returns it as a [ProductReviewProduct] or null if not found.
     */
    private suspend fun getProductByRemoteId(remoteProductId: Long): ProductReviewProduct? {
        return withContext(Dispatchers.IO) {
            productStore.getProductByRemoteId(selectedSite.get(), remoteProductId)?.let {
                ProductReviewProduct(it.remoteProductId, it.name, it.externalUrl)
            }
        }
    }

    /**
     * Returns a map of [ProductReviewProduct] by the remote_product_id pulled from the db.
     */
    private suspend fun getProductsByRemoteIdMap(remoteProductIds: List<Long>): Map<Long, ProductReviewProduct?> {
        return withContext(Dispatchers.IO) {
            remoteProductIds.associateWith { getProductByRemoteId(it) }
        }
    }

    @SuppressWarnings("unused")
    @Subscribe(threadMode = MAIN)
    fun onProductChanged(event: OnProductChanged) {
        if (event.causeOfChange == FETCH_PRODUCTS) {
            if (event.isError) {
                // TODO AMANDA : track fetch products failed
                WooLog.e(REVIEWS, "Error fetching matching product for product review: ${event.error.message}")
                continuationProduct?.resume(false)
            } else {
                // TODO AMANDA : track fetch products success
                continuationProduct?.resume(true)
            }
        }
    }

    @SuppressWarnings("unused")
    @Subscribe(threadMode = MAIN)
    fun onProductReviewChanged(event: OnProductReviewChanged) {
        if (event.causeOfChange == FETCH_PRODUCT_REVIEWS) {
            isFetchingProductReviews = false
            if (event.isError) {
                // TODO AMANDA : track fetch product reviews failed
                WooLog.e(REVIEWS, "Error fetching product review: ${event.error.message}")
                continuationReview?.resume(false)
            } else {
                // TODO AMANDA : track fetch product reviews success
                canLoadMoreReviews = event.canLoadMore
                continuationReview?.resume(true)
            }
        }
    }
}
