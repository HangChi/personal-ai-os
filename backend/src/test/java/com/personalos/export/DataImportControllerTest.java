package com.personalos.export;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
class DataImportControllerTest {
    @Test void rejectsDeclaredAndActualOversizeBeforeImporting() {
        var service = mock(DataImportService.class);
        var controller = new DataImportController(service);
        var request = mock(HttpServletRequest.class);
        when(request.getContentLengthLong()).thenReturn(20L * 1024 * 1024 + 1);
        assertThatThrownBy(() -> controller.importData("{}", request)).isInstanceOf(BusinessException.class);
        when(request.getContentLengthLong()).thenReturn(-1L);
        for (String content : new String[]{null, "   ", "中".repeat(7 * 1024 * 1024)})
            assertThatThrownBy(() -> controller.importData(content, request)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(service);
        controller.importData("{}", request);
        verify(service).importAll("{}");
    }
}
