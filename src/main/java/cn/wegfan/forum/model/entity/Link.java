package cn.wegfan.forum.model.entity;

import lombok.Data;
import org.apache.ibatis.type.Alias;

import java.io.Serializable;
import java.util.Date;

/**
 * 友情链接
 */
@Data
@Alias("Link")
public class Link implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 友情链接编号
     */
    private Long id;

    /**
     * 名称 最大30字符
     */
    private String name;

    /**
     * 网址
     */
    private String url;

    /**
     * 图标地址
     */
    private String iconUrl;

    /**
     * 顺序 小的先显示 1-100
     */
    private Integer order;

    /**
     * 描述 最大200字符
     */
    private String description;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * 删除时间
     */
    private Date deleteTime;

}
