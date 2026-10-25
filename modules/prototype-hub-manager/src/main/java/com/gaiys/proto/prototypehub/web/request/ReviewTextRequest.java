package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 纯文本追加请求；strip后校验长度和Unicode非空，不解释HTML或Markdown。 */
@Getter
@Setter
@Schema(description = "纯文本追加请求；strip后校验长度和Unicode非空，不解释HTML或Markdown。")
public class ReviewTextRequest extends ReviewPageRequest {
    @NotBlank
    @Size(max = 4000)
    @Pattern(regexp = "(?s).*[^\\p{javaWhitespace}\\p{IsWhite_Space}].*")
    @Schema(description = "一至四千Java字符的纯文本正文")
    private String text;

    @NotBlank
    @Pattern(regexp = "[!-~]{1,128}")
    @Schema(description = "强制客户端幂等请求键，不同语义提交必须更换")
    private String clientRequestId;

    public void setText(String text) {
        this.text = text == null ? null : text.strip();
    }
}
