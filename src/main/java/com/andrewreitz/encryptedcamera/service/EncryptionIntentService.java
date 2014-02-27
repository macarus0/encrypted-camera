package com.andrewreitz.encryptedcamera.service;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;

import com.andrewreitz.encryptedcamera.EncryptedCameraApp;
import com.andrewreitz.encryptedcamera.di.annotation.EncryptedDirectory;
import com.andrewreitz.encryptedcamera.di.annotation.EncryptionErrorNotification;
import com.andrewreitz.encryptedcamera.di.annotation.EncryptionNotification;
import com.andrewreitz.encryptedcamera.di.annotation.InternalDecryptedDirectory;
import com.andrewreitz.encryptedcamera.encryption.EncryptionProvider;
import com.andrewreitz.encryptedcamera.filesystem.SecureDelete;
import com.andrewreitz.encryptedcamera.sharedpreference.EncryptedCameraPreferenceManager;
import com.google.common.io.Files;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;

import javax.inject.Inject;

import timber.log.Timber;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * @author areitz
 */
public class EncryptionIntentService extends IntentService {

    private static final String UNENCRYPTED_FILE_PATH = "unencrypted_file_path";
    private static final String ENCRYPT_ACTION = "action_encrypt";

    private static final int NOTIFICATION_ERROR_ID = 1337;
    private static final int NOTIFICATION_ENCRYPTING_ID = 1338;

    @Inject EncryptionProvider encryptionProvider;
    @Inject @EncryptedDirectory File encryptedFileDirectory;
    @Inject SecureDelete secureDelete;
    @Inject NotificationManager notificationManager;
    @Inject @EncryptionErrorNotification Notification errorNotification;
    @Inject EncryptedCameraPreferenceManager preferenceManager;
    @Inject @InternalDecryptedDirectory File internalDecryptedDirectory;
    @Inject @EncryptionNotification Notification encryptingNotification;

    public EncryptionIntentService() {
        super(EncryptionIntentService.class.getName());
    }

    /**
     * Creates an intent and queues it up to the intent service
     *
     * @param context             the applications context
     * @param unencryptedFilePath unencrypted file path
     */
    public static void startEncryptAction(@NotNull Context context, @NotNull final String unencryptedFilePath) {
        Intent intent = new Intent(context.getApplicationContext(), EncryptionIntentService.class);
        intent.setAction(ENCRYPT_ACTION);
        intent.putExtra(UNENCRYPTED_FILE_PATH, checkNotNull(unencryptedFilePath));
        context.startService(intent);
    }

    @Override protected void onHandleIntent(Intent intent) {
        EncryptedCameraApp.get(getApplicationContext()).inject(this);

        switch (intent.getAction()) {
            case ENCRYPT_ACTION:
                handleEncrypt(intent);
                break;
            default:
                throw new IllegalArgumentException("Unknown action == " + intent.getAction());
        }
    }

    @Override public void onDestroy() {
        super.onDestroy();
        preferenceManager.setIsDecrypting(false);
        notificationManager.cancel(NOTIFICATION_ENCRYPTING_ID);
    }

    private void handleEncrypt(Intent intent) {
        notificationManager.notify(NOTIFICATION_ENCRYPTING_ID, encryptingNotification);
        String serializableExtra = checkNotNull(intent.getStringExtra(UNENCRYPTED_FILE_PATH));
        preferenceManager.setIsDecrypting(true);

        final File unencryptedFile = new File(serializableExtra);
        File encryptedFile = new File(encryptedFileDirectory, unencryptedFile.getName());
        File unencryptedInternal = new File(internalDecryptedDirectory, encryptedFile.getName());

        try {
            // Copy the file internally so the user can't mess with it
            Files.copy(unencryptedFile, unencryptedInternal);
            secureDelete.secureDelete(encryptedFile);

            //noinspection ResultOfMethodCallIgnored
            encryptedFile.createNewFile();
            encryptionProvider.encrypt(unencryptedFile, encryptedFile);
            // File encrypted now delete the original
            // Do this on a separate thread to hopefully speed this process up.
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        secureDelete.secureDelete(unencryptedFile);
                    } catch (IOException e) {
                        // TODO
                    }
                }
            }).run();
        } catch (IOException | InvalidKeyException | InvalidAlgorithmParameterException e) {
            Timber.e(e, "Error encrypting and saving image");
            notificationManager.notify(
                    NOTIFICATION_ERROR_ID,
                    errorNotification
            );
        }
    }
}