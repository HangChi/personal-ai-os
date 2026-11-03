import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.util.unit.DataSize;

/** 直接调用基线Boot转换器，保留null与Java比Go更大的Duration范围。 */
public class ConfigScalarOracle {
    public static void main(String[] args) throws Exception {
        var converter = ApplicationConversionService.getSharedInstance();
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line; (line = input.readLine()) != null;) {
            String[] fields = line.split("\t", -1);
            String text = new String(Base64.getDecoder().decode(fields[1]), StandardCharsets.UTF_8);
            try {
                Object converted = fields[0].equals("duration") ? converter.convert(text, Duration.class)
                        : converter.convert(text, DataSize.class);
                if (converted == null) {
                    System.out.println("null");
                } else if (converted instanceof Duration duration) {
                    BigInteger nanos = BigInteger.valueOf(duration.getSeconds()).multiply(BigInteger.valueOf(1_000_000_000L))
                            .add(BigInteger.valueOf(duration.getNano()));
                    System.out.println("value\t" + nanos);
                } else {
                    System.out.println("value\t" + ((DataSize) converted).toBytes());
                }
            } catch (RuntimeException ex) {
                System.out.println("error");
            }
        }
    }
}
