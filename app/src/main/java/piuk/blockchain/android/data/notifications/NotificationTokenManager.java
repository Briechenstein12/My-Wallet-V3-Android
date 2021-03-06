package piuk.blockchain.android.data.notifications;

import com.google.firebase.iid.FirebaseInstanceId;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import info.blockchain.wallet.payload.PayloadManager;
import info.blockchain.wallet.payload.data.Wallet;

import io.reactivex.Completable;
import io.reactivex.schedulers.Schedulers;
import piuk.blockchain.android.data.access.AccessState;
import piuk.blockchain.android.data.access.AuthEvent;
import piuk.blockchain.android.data.rxjava.RxBus;
import piuk.blockchain.android.data.rxjava.RxUtil;
import piuk.blockchain.android.util.PrefsUtil;
import timber.log.Timber;

public class NotificationTokenManager {

    private NotificationService notificationService;
    private AccessState accessState;
    private PayloadManager payloadManager;
    private PrefsUtil prefsUtil;
    private RxBus rxBus;

    public NotificationTokenManager(NotificationService notificationService,
                                    AccessState accessState,
                                    PayloadManager payloadManager,
                                    PrefsUtil prefsUtil,
                                    RxBus rxBus) {
        this.notificationService = notificationService;
        this.accessState = accessState;
        this.payloadManager = payloadManager;
        this.prefsUtil = prefsUtil;
        this.rxBus = rxBus;
    }

    /**
     * Sends the access token to the update-firebase endpoint once the user is logged in fully, as
     * the token is generally only generated on first install or first start after updating.
     *
     * @param token A Firebase access token
     */
    void storeAndUpdateToken(@NonNull String token) {
        if (accessState.isLoggedIn()) {
            // Send token
            sendFirebaseToken(token);
        } else {
            // Store token and send once login event happens
            rxBus.register(AuthEvent.class).subscribe(authEvent -> {
                if (authEvent == AuthEvent.LOGIN) {
                    // Send token
                    sendFirebaseToken(token);
                }
            }, Throwable::printStackTrace);
        }

        prefsUtil.setValue(PrefsUtil.KEY_FIREBASE_TOKEN, token);
    }

    // TODO: 28/11/2016 This may want to be transformed into an Observable?

    /**
     * Returns the stored Firebase token, otherwise attempts to trigger a refresh of the token which
     * will be handled appropriately by {@link FcmCallbackService}
     *
     * @return The Firebase token
     */
    @Nullable
    private String getFirebaseToken() {
        return !prefsUtil.getValue(PrefsUtil.KEY_FIREBASE_TOKEN, "").isEmpty()
                ? prefsUtil.getValue(PrefsUtil.KEY_FIREBASE_TOKEN, "")
                : FirebaseInstanceId.getInstance().getToken();
    }

    // TODO: 16/01/2017 Call me on logout?

    /**
     * Resets Instance ID and revokes all tokens. Clears stored token if successful
     */
    public Completable revokeAccessToken() {
        return Completable.fromCallable(() -> {
            FirebaseInstanceId.getInstance().deleteInstanceId();
            return Void.TYPE;
        }).doOnComplete(this::clearStoredToken)
                .compose(RxUtil.applySchedulersToCompletable());
    }

    /**
     * Re-sends the notification token. The token is only ever generated on app installation, so it
     * may be preferable to store the token and resend it on startup rather than have an app end up
     * in a state where it may not have registered with the right endpoint or similar.
     */
    public void resendNotificationToken() {
        if (getFirebaseToken() != null) {
            sendFirebaseToken(getFirebaseToken());
        }
    }

    private void sendFirebaseToken(String refreshedToken) {
        Wallet payload = payloadManager.getPayload();
        String guid = payload.getGuid();
        String sharedKey = payload.getSharedKey();

        // TODO: 09/11/2016 Decide what to do if sending fails, perhaps retry?
        notificationService.sendNotificationToken(refreshedToken, guid, sharedKey)
                .subscribeOn(Schedulers.io())
                .subscribe(() -> Timber.d("sendFirebaseToken: success"), Throwable::printStackTrace);
    }

    /**
     * Removes the stored token from Shared Preferences
     */
    private void clearStoredToken() {
        prefsUtil.removeValue(PrefsUtil.KEY_FIREBASE_TOKEN);
    }

}
