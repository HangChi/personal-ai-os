package com.personalos.chat.controller;

import com.personalos.common.ai.config.AudioTranscriptionService;
import com.personalos.common.ai.config.dto.TranscriptionResult;
import com.personalos.common.api.ApiResponse;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import java.io.IOException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 语音转写接口（聊天页麦克风按钮的后端）。
 *
 * <h2>为什么放在 chat 包而不是 settings 包</h2>
 * 它是聊天输入的一部分，服务对象是「正在打字的用户」，不是「在配模型的用户」。
 * 配置页只需要知道「配了哪个语音模型」，不需要知道录音怎么上传。
 *
 * <h2>未配置语音模型时返回 200 + available=false</h2>
 * 这是本接口最重要的一条约定。「没有语音模型」是一种**可正常工作的状态**
 * （前端的语音输入回落到浏览器自带识别），不是错误。若这里返回 4xx/5xx，
 * 前端就得在 catch 里区分「真的坏了」与「本来就没有」——
 * 而那个区分只能靠错误码或文案，两边一旦不同步，用户就会看到
 * 「明明能用的语音输入突然报错」。
 */
@RestController
@RequestMapping("/api/v1/chat/transcribe")
public class AudioTranscriptionController {

    private final AudioTranscriptionService transcriptionService;

    public AudioTranscriptionController(AudioTranscriptionService transcriptionService) {
        this.transcriptionService = transcriptionService;
    }

    /**
     * 前端启动录音前先问一次「走哪条路」。
     *
     * <p>不省这一步的代价很具体：若前端直接录音上传，没配语音模型的用户会
     * 先对着麦克风说一句话、等一次往返，才被告知「没配模型」——
     * 而这件事本来是可以在按按钮前就知道的。
     *
     * @return {@code available=true} 表示走后端转写；false 表示应回落浏览器识别，
     *         原因在 {@code reason} 里
     */
    @GetMapping("/status")
    public ApiResponse<StatusDto> status() {
        String reason = transcriptionService.blockReason();
        return ApiResponse.ok(new StatusDto(reason == null, reason));
    }

    /**
     * 上传一段录音，返回转写文本。
     *
     * <p>用 multipart 而不是 JSON + base64：base64 会把体积撑大 33%，
     * 而录音本来就是这个接口里最大的负载。
     *
     * @param file 录音文件（webm / opus / wav / mp3）
     * @return 转写结果；不可用时 {@code available=false}，失败时 {@code error} 非空
     */
    @PostMapping
    public ApiResponse<TranscriptionResult> transcribe(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "录音文件不能为空");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "读取录音文件失败，请重试");
        }
        String filename = file.getOriginalFilename();
        return ApiResponse.ok(transcriptionService.transcribe(bytes, filename, file.getContentType()));
    }

    /**
     * 转写能力的状态。
     *
     * @param available 是否可用（= 生效配置里有语音模型且有密钥）
     * @param reason    不可用的原因；可用时为 null
     */
    public record StatusDto(boolean available, String reason) {
    }
}
