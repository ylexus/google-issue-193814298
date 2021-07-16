package net.yudichev.googleissue193814298;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.About;
import com.google.auth.oauth2.UserCredentials;
import com.google.photos.library.v1.PhotosLibraryClient;
import com.google.photos.library.v1.PhotosLibrarySettings;
import com.google.photos.library.v1.proto.BatchCreateMediaItemsResponse;
import com.google.photos.library.v1.proto.NewMediaItem;
import com.google.photos.library.v1.proto.SimpleMediaItem;
import com.google.photos.library.v1.upload.UploadMediaItemRequest;
import com.google.photos.library.v1.upload.UploadMediaItemResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static com.google.api.services.drive.DriveScopes.DRIVE_APPDATA;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.MINUTES;

public class Main {
    private static final String PHOTOS_LIBRARY = "https://www.googleapis.com/auth/photoslibrary";
    private final ScheduledExecutorService executorService;
    private final PhotosLibraryClient photosClient;
    private final Path mediaDataFilePath;
    private final Drive driveClient;

    public Main(String[] args) throws IOException, GeneralSecurityException {
        executorService = Executors.newScheduledThreadPool(1);

        if (args.length != 2) {
            throw new IllegalArgumentException(String.format("Usage: %s <path_to_google_client_secret> <path_to_media_file>", Main.class.getName()));
        }
        Path clientSecretPath = Paths.get(args[0]);
        mediaDataFilePath = Paths.get(args[1]);
        GoogleClientSecrets clientSecrets;
        JacksonFactory jsonFactory = JacksonFactory.getDefaultInstance();
        try (BufferedReader reader = Files.newBufferedReader(clientSecretPath)) {
            clientSecrets = GoogleClientSecrets.load(jsonFactory, reader);
        }
        NetHttpTransport netHttpTransport = GoogleNetHttpTransport.newTrustedTransport();
        Path authDataDir = Paths.get(System.getProperty("java.io.tmpdir"), "google-auth");
        Files.createDirectories(authDataDir);
        GoogleAuthorizationCodeFlow flow =
                new GoogleAuthorizationCodeFlow.Builder(netHttpTransport, jsonFactory, clientSecrets, asList(DRIVE_APPDATA, PHOTOS_LIBRARY))
                        .setDataStoreFactory(new FileDataStoreFactory(authDataDir.toFile()))
                        .setAccessType("offline")
                        .build();
        LocalServerReceiver receiver = new LocalServerReceiver.Builder()
                .setPort(8888)
                .build();
        Credential credential = new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");

        photosClient = PhotosLibraryClient.initialize(
                PhotosLibrarySettings.newBuilder()
                        .setCredentialsProvider(FixedCredentialsProvider
                                                        .create(UserCredentials.newBuilder()
                                                                        .setClientId(clientSecrets.getDetails().getClientId())
                                                                        .setClientSecret(clientSecrets.getDetails().getClientSecret())
                                                                        .setRefreshToken(credential.getRefreshToken())
                                                                        .build()))
                        .build());
        driveClient = new Drive.Builder(credential.getTransport(), JacksonFactory.getDefaultInstance(), credential)
                .setApplicationName("google-issue-193814298")
                .build();
    }

    public static void main(String[] args) throws IOException, GeneralSecurityException, InterruptedException {
        new Main(args).start();
        Thread.currentThread().join();
    }

    private void start() {
        executorService.scheduleAtFixedRate(this::printUsage, 0, 1, MINUTES);
        executorService.execute(this::uploadMediaItem);
    }

    private void printUsage() {
        try {
            Drive.Files.Create fileCreateRequest = driveClient.files().create(
                    new com.google.api.services.drive.model.File()
                            .setName("file.txt")
                            .setParents(singletonList("appDataFolder")),
                    new ByteArrayContent("text/plain", new byte[0]))
                    .setFields("id");
            log("OUT: " + fileCreateRequest);
            com.google.api.services.drive.model.File fileCreateResponse = fileCreateRequest.execute();
            log("IN : " + fileCreateResponse);

            Drive.About.Get aboutGetRequest = driveClient.about().get().setFields("storageQuota/limit, storageQuota/usage");
            log("OUT: " + aboutGetRequest);
            About aboutGetResponse = aboutGetRequest.execute();
            log("IN : " + aboutGetResponse);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void uploadMediaItem() {
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(mediaDataFilePath.toFile(), "r")) {
            UploadMediaItemRequest uploadRequest = UploadMediaItemRequest.newBuilder()
                    .setDataFile(randomAccessFile)
                    .setMimeType("application/octet-stream")
                    .build();
            log("OUT: " + uploadRequest);
            UploadMediaItemResponse uploadResponse = photosClient.uploadMediaItem(uploadRequest);
            log("IN : " + uploadResponse);
            List<NewMediaItem> newMediaItems = singletonList(
                    NewMediaItem.newBuilder()
                            .setSimpleMediaItem(SimpleMediaItem.newBuilder()
                                                        .setUploadToken(uploadResponse.getUploadToken().get())
                                                        .setFileName(mediaDataFilePath.getFileName().toString())
                                                        .build())
                            .build());
            log("OUT: " + newMediaItems);
            BatchCreateMediaItemsResponse response = photosClient.batchCreateMediaItems(newMediaItems);
            log("IN : " + response);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void log(String message) {
        System.out.println(Instant.now().toString() + " " + message);
    }
}
