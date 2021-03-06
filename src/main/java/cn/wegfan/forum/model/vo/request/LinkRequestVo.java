package cn.wegfan.forum.model.vo.request;

import lombok.Data;
import org.hibernate.validator.constraints.Length;
import org.hibernate.validator.constraints.Range;

import javax.annotation.Nullable;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serializable;

@Data
public class LinkRequestVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 友情链接编号
     */
    @Nullable
    private Long id;

    /**
     * 名称 最大30字符
     */
    @NotNull
    @NotBlank
    @Length(max = 30)
    private String name;

    /**
     * 网址
     */
    @NotNull
    @Length(max = 255)
    private String url;

    /**
     * 图标地址
     */
    @Length(max = 255)
    @Nullable
    private String iconUrl;

    /**
     * 顺序 小的先显示 1-100
     */
    @NotNull
    @Range(min = 1, max = 100)
    private Integer order;

    /**
     * 描述 最大200字符
     */
    @NotNull
    @Length(max = 200)
    private String description;

}

