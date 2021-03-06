package cn.wegfan.forum.config;

import cn.hutool.core.io.IORuntimeException;
import cn.wegfan.forum.constant.BusinessErrorEnum;
import cn.wegfan.forum.model.vo.response.ResultVo;
import cn.wegfan.forum.util.BusinessException;
import cn.wegfan.forum.util.ValidateException;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.authz.UnauthenticatedException;
import org.apache.shiro.authz.UnauthorizedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mail.MailException;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * 全局异常处理
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 当前生效的项目配置名称
     */
    @Value("${spring.profiles.active}")
    private String activeProfile;

    @Autowired
    private HttpServletRequest request;

    /**
     * 处理IO异常
     */
    @ExceptionHandler(IORuntimeException.class)
    public ResultVo handleIoRuntimeException(IORuntimeException e) {
        log.warn("<{}> IO异常：{}", request.getRequestURI(), e.getMessage());
        return ResultVo.businessError(new BusinessException(BusinessErrorEnum.FILE_NOT_FOUND));
    }

    /**
     * 处理发送邮件失败
     */
    @ExceptionHandler(MailException.class)
    public ResultVo handleMailException(MailException e) {
        log.error("<{}> 发送邮件失败：{}", request.getRequestURI(), e.getMessage());
        return ResultVo.businessError(new BusinessException(BusinessErrorEnum.SEND_MAIL_FAILED));
    }

    /**
     * 处理上传文件大小超过限制
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResultVo handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        log.warn("<{}> 上传文件大小超过限制：{}", request.getRequestURI(), e.getMessage());
        return ResultVo.businessError(new BusinessException(BusinessErrorEnum.UPLOAD_FILE_TOO_LARGE));
    }

    /**
     * 处理用户没有登录的请求
     */
    @ExceptionHandler(UnauthenticatedException.class)
    public ResultVo handleUnauthenticatedException(UnauthenticatedException e) {
        log.warn("<{}> 用户没有登录：{}", request.getRequestURI(), e.getMessage());
        return ResultVo.businessError(new BusinessException(BusinessErrorEnum.USER_NOT_LOGIN));
    }

    /**
     * 处理没有权限的请求
     */
    @ExceptionHandler(UnauthorizedException.class)
    public ResultVo handleUnauthorizedException(UnauthorizedException e) {
        log.warn("<{}> 用户没有权限：{}", request.getRequestURI(), e.getMessage());
        return ResultVo.businessError(new BusinessException(BusinessErrorEnum.UNAUTHORIZED));
    }

    /**
     * 处理请求主体类型异常
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResultVo handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        log.warn("<{}> 请求主体类型异常：{}", request.getRequestURI(), e.getMessage());
        return ResultVo.businessError(new BusinessException(BusinessErrorEnum.VALIDATION_ERROR));
    }

    /**
     * 处理请求参数校验失败
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResultVo handleInvalidArgumentException(MethodArgumentNotValidException e) {
        log.warn("<{}> 参数校验失败，输入值：{}", request.getRequestURI(), e.getBindingResult().getTarget());
        List<ObjectError> errors = e.getBindingResult().getAllErrors();
        return ResultVo.validationError(errors);
    }

    /**
     * 处理手动校验参数失败
     */
    @ExceptionHandler(ValidateException.class)
    public ResultVo handleValidateException(ValidateException e) {
        log.warn("<{}> 参数校验失败，输入值：{}", request.getRequestURI(), e.getInputObject());
        return ResultVo.validationError(e.getErrorList());
    }

    /**
     * 处理业务异常
     */
    @ExceptionHandler(BusinessException.class)
    public ResultVo handleBusinessException(BusinessException e) {
        log.warn("<{}> 业务异常：{}", request.getRequestURI(), e.getMessage());
        return ResultVo.businessError(e);
    }

    /**
     * 处理 GET 参数校验失败
     */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResultVo handleNumberFormatException(MethodArgumentTypeMismatchException e) {
        log.warn("<{}> GET参数校验失败：{}", request.getRequestURI(), e.getMessage());
        return ResultVo.error(400, "搜索条件不合法");
    }

    /**
     * 处理其他异常
     */
    @ExceptionHandler(Exception.class)
    public ResultVo handleException(Exception e) {
        log.error("<{}> 其他异常：{}", request.getRequestURI(), e.getMessage());
        log.error("", e);
        // 如果是生产环境则不显示错误信息
        if ("prod".equals(activeProfile)) {
            return ResultVo.businessError(new BusinessException(BusinessErrorEnum.INTERNAL_SERVER_ERROR));
        }
        return ResultVo.error(BusinessErrorEnum.INTERNAL_SERVER_ERROR.getErrorCode(),
                String.format("内部服务器错误，错误信息：%s", e.toString()));
    }

}
