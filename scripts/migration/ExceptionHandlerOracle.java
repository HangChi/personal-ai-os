import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.personalos.common.api.ApiResponse;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.exception.GlobalExceptionHandler;
import com.personalos.common.exception.ResourceNotFoundException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 只直接调用固定基线处理器；不启动应用/数据库，不模拟完整Servlet分派。 */
public class ExceptionHandlerOracle {
    private static final GlobalExceptionHandler HANDLER = new GlobalExceptionHandler();
    private static final ListAppender<ILoggingEvent> EVENTS = new ListAppender<>();

    public static void endpoint(String value) {}

    private static String encode(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void emit(String name, ResponseEntity<ApiResponse<Void>> result) {
        ApiResponse<Void> body = result.getBody();
        if (body == null || body.data() != null || body.timestamp() == null) {
            throw new IllegalStateException("错误信封必须有null data和时间");
        }
        System.out.println(String.join("\t", "CASE", name, Integer.toString(result.getStatusCode().value()),
                Integer.toString(body.code()), encode(body.message()), encode(result.getHeaders().getContentType().toString())));
    }

    @SuppressWarnings("unchecked")
    private static ConstraintViolation<Object> violation(String property, String message) {
        Path path = new Path() {
            public Iterator<Path.Node> iterator() { return List.<Path.Node>of().iterator(); }
            public String toString() { return property; }
        };
        return (ConstraintViolation<Object>) Proxy.newProxyInstance(ExceptionHandlerOracle.class.getClassLoader(),
                new Class<?>[]{ConstraintViolation.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getPropertyPath" -> path;
                    case "getMessage" -> message;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    public static void main(String[] args) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logger.setAdditive(false);
        logger.setLevel(Level.DEBUG);
        EVENTS.start();
        logger.addAppender(EVENTS);
        for (ErrorCode code : ErrorCode.values()) {
            if (code != ErrorCode.SUCCESS) emit("business-" + code.getCode(), HANDLER.handleBusinessException(new BusinessException(code)));
        }
        emit("business-custom", HANDLER.handleBusinessException(new BusinessException(ErrorCode.BUSINESS_CONFLICT, "任务已完成😀")));
        emit("business-empty", HANDLER.handleBusinessException(new BusinessException(ErrorCode.BUSINESS_CONFLICT, "")));
        emit("resource-custom", HANDLER.handleBusinessException(new ResourceNotFoundException("指定任务不存在")));
        MethodParameter parameter = new MethodParameter(ExceptionHandlerOracle.class.getMethod("endpoint", String.class), 0);
        for (boolean populated : List.of(false, true)) {
            BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "request");
            if (populated) {
                binding.addError(new FieldError("request", "title", "不能为空"));
                binding.addError(new FieldError("request", "tags[0]", "长度超限😀"));
            }
            emit("validation-" + populated, HANDLER.handleMethodArgumentNotValid(new MethodArgumentNotValidException(parameter, binding)));
        }
        Set<ConstraintViolation<?>> violations = new LinkedHashSet<>();
        violations.add(violation("list.page", "必须大于等于0"));
        violations.add(violation("list.size", "必须小于等于200"));
        emit("constraint", HANDLER.handleConstraintViolation(new ConstraintViolationException(violations)));
        emit("constraint-empty", HANDLER.handleConstraintViolation(new ConstraintViolationException(Set.of())));
        emit("type", HANDLER.handleTypeMismatch(new MethodArgumentTypeMismatchException("private-invalid", Integer.class, "page", parameter, null)));
        emit("not-found-resource", HANDLER.handleNoResourceFound(new NoResourceFoundException(HttpMethod.GET, "/missing", "missing")));
        emit("not-found-handler", HANDLER.handleNoHandlerFound(new NoHandlerFoundException("GET", "/missing", new org.springframework.http.HttpHeaders())));
        emit("upload", HANDLER.handleMaxUploadSize(new MaxUploadSizeExceededException(1)));
        emit("json", HANDLER.handleMessageNotReadable(new HttpMessageNotReadableException("fixture-private-body", new MockHttpInputMessage(new byte[0]))));
        emit("missing-query", HANDLER.handleMissingParameter(new MissingServletRequestParameterException("page", "int")));
        emit("missing-part", HANDLER.handleMissingParameter(new MissingServletRequestPartException("file")));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/settings/model/profiles/42");
        request.setQueryString("keyword=fixture-private-query");
        EVENTS.list.clear();
        emit("method", HANDLER.handleMethodNotSupported(new HttpRequestMethodNotSupportedException("GET", List.of("PUT", "DELETE")), request));
        ILoggingEvent event = EVENTS.list.getFirst();
        if (event.getLevel() != Level.WARN || !event.getFormattedMessage().contains(request.getRequestURI())
                || event.getFormattedMessage().contains("fixture-private-query")) throw new IllegalStateException("405日志契约失败");
        emit("accept", HANDLER.handleNotAcceptable(new HttpMediaTypeNotAcceptableException(List.of(MediaType.TEXT_MARKDOWN)), request));
        emit("integrity", HANDLER.handleDataIntegrityViolation(new DataIntegrityViolationException("fixture-private-sql")));
        emit("unexpected", HANDLER.handleUnexpectedException(new Exception("fixture-private-error")));
        EVENTS.list.clear();
        HANDLER.handleClientDisconnected(new AsyncRequestNotUsableException("fixture-private-disconnect"), request);
        if (EVENTS.list.size() != 1 || EVENTS.list.getFirst().getLevel() != Level.DEBUG) throw new IllegalStateException("断连日志契约失败");
        System.out.println("DISCONNECT\tDEBUG\tno-body");
    }
}
