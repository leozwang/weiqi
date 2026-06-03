# Implementation Plan: In-App Purchases (IAP) with Google Play Billing

This document outlines the complete process, setup requirements, and code architecture for adding In-App Purchases (IAP) to the **weiqi** Android application. 

We will use the **Google Play Billing Library** (v7.0.0), which is the standard, secure method to sell digital products and subscriptions in Android apps.

---

## Phase 1: Google Play Console & Merchant Setup
Before writing code, you must configure your developer account to receive payments:

1. **Google Play Developer Account**: If you don't have one, register at [Google Play Console](https://play.google.com/console).
2. **Set up a Payments Merchant Profile**: Link your bank account in Play Console under **Settings** > **Payment settings**.
3. **Upload a Draft Build**: 
   * The Play Console requires an APK/AAB containing the Billing permission to enable product creation.
   * We will add the permission to `AndroidManifest.xml`, build the release APK/AAB using Bazel, and upload it to an internal testing track.
4. **Configure Products**:
   * In the Play Console, navigate to **Monetization** > **In-app products** (or **Subscriptions**).
   * Click **Create product**.
   * Set a unique **Product ID** (e.g., `premium_unlock`, `ai_pro_tier`).
   * Define the title, description, and price.

---

## Phase 2: Build System Configuration (Bazel)

### 1. Update `AndroidManifest.xml`
Add the Billing permission so Google Play knows the app supports transactions.

```xml
<uses-permission android:name="com.android.vending.BILLING" />
```

### 2. Add Maven Dependency in `MODULE.bazel`
Add the KTX-enabled Google Play Billing library inside the `maven.install` block:

```kotlin
"com.android.billingclient:billing-ktx:7.0.0"
```

---

## Phase 3: Architecture & Core Implementation

We will create a helper class `BillingManager.kt` to isolate Play Billing logic from the UI.

```
src/java/com/cwave/weiqi/
├── katago/
├── BillingManager.kt       <-- New IAP Helper Class
├── GameFragment.kt         <-- UI Integration
└── MainActivity.kt
```

### 1. Create `BillingManager.kt`
This class manages connection state, product querying, purchase execution, and confirmation:

```kotlin
package com.cwave.weiqi

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BillingManager(
    private val context: Context,
    private val externalScope: CoroutineScope
) : PurchasesUpdatedListener {

    companion object {
        private const val TAG = "BillingManager"
        
        // Your product ID configured in the Play Console
        const val PREMIUM_UNLOCK_ID = "premium_unlock"
    }

    private val billingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases()
        .build()

    private val _isPremiumUnlocked = MutableStateFlow(false)
    val isPremiumUnlocked: StateFlow<Boolean> = _isPremiumUnlocked

    private val _productDetails = MutableStateFlow<ProductDetails?>(null)
    val productDetails: StateFlow<ProductDetails?> = _productDetails

    init {
        startBillingConnection()
    }

    private fun startBillingConnection() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.i(TAG, "Billing Setup Successful.")
                    queryPurchases()
                    queryProductDetails()
                } else {
                    Log.e(TAG, "Billing Setup Failed: ${billingResult.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing Service disconnected. Retrying...")
                // Re-try connection with exponential backoff in a production app
            }
        })
    }

    /**
     * Check what products the user currently owns.
     */
    fun queryPurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchaseList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                var unlocked = false
                for (purchase in purchaseList) {
                    if (purchase.products.contains(PREMIUM_UNLOCK_ID) &&
                        purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                    ) {
                        unlocked = true
                        handlePurchase(purchase)
                    }
                }
                _isPremiumUnlocked.value = unlocked
            }
        }
    }

    /**
     * Load detailed pricing information from Play Store for UI display.
     */
    private fun queryProductDetails() {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PREMIUM_UNLOCK_ID)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, detailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                _productDetails.value = detailsList.find { it.productId == PREMIUM_UNLOCK_ID }
            } else {
                Log.e(TAG, "Failed querying product details: ${billingResult.debugMessage}")
            }
        }
    }

    /**
     * Launch Google Play's native payment overlay screen.
     */
    fun launchPurchaseFlow(activity: Activity) {
        val details = _productDetails.value
        if (details == null) {
            Log.e(TAG, "Product details not loaded yet!")
            return
        }

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build()
                )
            )
            .build()

        billingClient.launchBillingFlow(activity, flowParams)
    }

    /**
     * Callback executed by Google Play Billing when purchase status changes.
     */
    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                if (purchase.products.contains(PREMIUM_UNLOCK_ID)) {
                    handlePurchase(purchase)
                }
            }
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            Log.i(TAG, "User canceled purchase flow.")
        } else {
            Log.e(TAG, "Purchase error: ${billingResult.debugMessage}")
        }
    }

    /**
     * Acknowledge purchase to prevent automatic refund after 3 days.
     */
    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            _isPremiumUnlocked.value = true
            
            if (!purchase.isAcknowledged) {
                val acknowledgeParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()

                externalScope.launch(Dispatchers.IO) {
                    billingClient.acknowledgePurchase(acknowledgeParams) { billingResult ->
                        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                            Log.i(TAG, "Purchase acknowledged successfully.")
                        } else {
                            Log.e(TAG, "Failed acknowledging purchase: ${billingResult.debugMessage}")
                        }
                    }
                }
            }
        }
    }
}
```

---

## Phase 4: UI & UX Gatekeeping in Compose

We can now restrict specific premium options behind IAP in `GameFragment.kt` (e.g., the highly-intensive **"Pro Tier (2500 Visits)"** AI strength setting, or custom models).

### Integration Outline:
1. **Instantiate `BillingManager`**: Create a single instance inside the Activity/Fragment and observe `isPremiumUnlocked` as a Compose state.
2. **Modify standard AI selectors**: If the user clicks a premium item while locked, trigger the Google Play purchase dialog.

### Example UI Code (`GameFragment.kt` updates):
```kotlin
// 1. Initialize Billing Manager in your Fragment
private lateinit var billingManager: BillingManager

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    billingManager = BillingManager(requireContext(), lifecycleScope)
}

// 2. In GameScreen Compose:
val isPremiumUnlocked by billingManager.isPremiumUnlocked.collectAsState()
val productDetails by billingManager.productDetails.collectAsState()

// 3. Inside AI Strength selection lazy row:
val isSelected = currentVisits == v
val isPremiumItem = v >= 2500 // Require premium unlock for 2500 visits

Surface(
    modifier = Modifier
        .size(width = 100.dp, height = 56.dp)
        .clip(RoundedCornerShape(16.dp))
        .pointerInput(v) {
            detectTapGestures {
                if (isPremiumItem && !isPremiumUnlocked) {
                    // Prompt purchase flow!
                    billingManager.launchPurchaseFlow(requireActivity())
                } else {
                    currentVisits = v
                }
            }
        },
    // Rest of styling ...
) {
    Box {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = label)
            Text(text = "$v visits")
        }
        
        // Overlay a padlock icon if premium is locked
        if (isPremiumItem && !isPremiumUnlocked) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Premium Locked",
                modifier = Modifier.align(Alignment.TopEnd).size(16.dp)
            )
        }
    }
}
```

---

## Phase 5: Testing & Security Best Practices

1. **Licensing Testing Accounts**: In Play Console under **Setup** > **License Testing**, register your developer/testing Gmail addresses. This allows you to make test purchases with a virtual credit card for free.
2. **Billing Library Security**:
   * For high security, **never perform critical verification solely on-device**. The billing signature should ideally be verified on a secure backend web-server via Google's Developer APIs.
   * Store the premium status using Encrypted SharedPreferences (provided by `androidx.security:security-crypto:1.1.0-alpha06` which is already added to your `MODULE.bazel`!).
