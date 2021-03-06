package cn.wegfan.forum.mapper;

import cn.wegfan.forum.model.entity.Permission;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PermissionMapper extends BaseMapper<Permission> {

    Permission selectForumPermissionByUserId(Long userId);

    Permission selectBoardPermissionByBoardId(Long boardId);

    Permission selectUserBoardPermissionByUserIdAndBoardId(Long userId, Long boardId);

    List<Permission> selectBoardPermissionListByUserId(Long userId);

}
