import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Synthetic inputs only; runs the baseline Spring encoder without starting the app. */
public final class BCryptOracle {
    public static void main(String[] args) throws Exception {
        var encoder = new BCryptPasswordEncoder();
        var decoder = Base64.getDecoder();
        var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            var fields = line.split("\t", -1);
            var configured = new String(decoder.decode(fields[0]), StandardCharsets.UTF_8);
            var candidate = new String(decoder.decode(fields[1]), StandardCharsets.UTF_8);
            try {
                var hash = BCrypt.hashpw(configured, "$2a$10$......................");
                var actual = encoder.encode(configured);
                System.out.println(hash + "\t" + encoder.matches(candidate, hash)
                        + "\t" + actual.substring(0, 7));
            } catch (IllegalArgumentException exception) {
                System.out.println("error:IllegalArgumentException\tfalse\t-");
            }
        }
    }
}
