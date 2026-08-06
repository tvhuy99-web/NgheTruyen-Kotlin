import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Small, dependency-free bootstrap for source archives that cannot carry the
 * Gradle Wrapper binary. The downloaded JAR is accepted only when its official
 * Gradle 8.13 SHA-256 checksum matches.
 */
public final class WrapperDownloader {
    private static final URI WRAPPER_URI = URI.create(
        "https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar"
    );
    private static final String EXPECTED_SHA256 =
        "81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f";
    private static final long MAX_WRAPPER_BYTES = 2L * 1024L * 1024L;

    private WrapperDownloader() {}

    public static void main(String[] args) throws Exception {
        Path destination = args.length == 0
            ? Path.of("gradle", "wrapper", "gradle-wrapper.jar")
            : Path.of(args[0]);

        if (Files.isRegularFile(destination) && EXPECTED_SHA256.equals(sha256(destination))) {
            System.out.println("Gradle Wrapper JAR is already verified.");
            return;
        }

        Files.createDirectories(destination.toAbsolutePath().getParent());
        Path temporary = destination.resolveSibling(destination.getFileName() + ".download");
        Files.deleteIfExists(temporary);

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        HttpRequest request = HttpRequest.newBuilder(WRAPPER_URI)
            .timeout(Duration.ofSeconds(60))
            .header("User-Agent", "NgheTruyen-Gradle-Wrapper-Bootstrap/1")
            .GET()
            .build();

        System.out.println("Downloading verified Gradle Wrapper JAR...");
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("Wrapper download failed with HTTP " + response.statusCode());
        }

        try (InputStream input = response.body()) {
            copyBounded(input, temporary, MAX_WRAPPER_BYTES);
        } catch (Throwable failure) {
            Files.deleteIfExists(temporary);
            throw failure;
        }

        String actual = sha256(temporary);
        if (!EXPECTED_SHA256.equals(actual)) {
            Files.deleteIfExists(temporary);
            throw new SecurityException(
                "Gradle Wrapper checksum mismatch. Expected " + EXPECTED_SHA256 + " but received " + actual
            );
        }

        try {
            Files.move(
                temporary,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println("Gradle Wrapper JAR verified and installed.");
    }

    private static void copyBounded(InputStream input, Path destination, long maximumBytes) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        try (var output = Files.newOutputStream(destination)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maximumBytes) {
                    throw new IOException("Wrapper download exceeds " + maximumBytes + " bytes");
                }
                output.write(buffer, 0, read);
            }
        }
        if (total == 0) {
            throw new IOException("Wrapper download was empty");
        }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
