package cn.wegfan.forum.service;

import cn.hutool.core.img.ImgUtil;
import cn.hutool.core.lang.UUID;
import cn.wegfan.forum.constant.BusinessErrorEnum;
import cn.wegfan.forum.constant.Constant;
import cn.wegfan.forum.constant.SexEnum;
import cn.wegfan.forum.constant.UserTypeEnum;
import cn.wegfan.forum.model.entity.Board;
import cn.wegfan.forum.model.entity.Category;
import cn.wegfan.forum.model.entity.Permission;
import cn.wegfan.forum.model.entity.User;
import cn.wegfan.forum.model.vo.request.*;
import cn.wegfan.forum.model.vo.response.*;
import cn.wegfan.forum.util.BusinessException;
import cn.wegfan.forum.util.IpUtil;
import cn.wegfan.forum.util.PasswordUtil;
import cn.wegfan.forum.util.SessionUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import ma.glasnost.orika.MapperFacade;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.subject.Subject;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
@Transactional(rollbackFor = Exception.class)
public class UserServiceFacade {

    @Autowired
    private MapperFacade mapperFacade;

    @Autowired
    private UserService userService;

    @Autowired
    private BoardAdminService boardAdminService;

    @Autowired
    private CategoryAdminService categoryAdminService;

    @Autowired
    private BoardService boardService;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private TopicService topicService;

    @Autowired
    private ReplyService replyService;

    @Autowired
    private AttachmentService attachmentService;

    @Autowired
    private CaptchaService captchaService;

    @Autowired
    private EmailService emailService;

    @Value("${spring.mail.username}")
    private String emailSender;

    public UserLoginResponseVo login(UserLoginRequestVo requestVo) {
        User user = userService.getNotDeletedUserByUsername(requestVo.getUsername());
        if (user == null) {
            throw new BusinessException(BusinessErrorEnum.WRONG_USERNAME_OR_PASSWORD);
        }
        Permission forumPermission = permissionService.getForumPermissionByUserId(user.getId());
        if (forumPermission.getBanVisit()) {
            throw new BusinessException(BusinessErrorEnum.ACCOUNT_DISABLED);
        }

        Subject subject = SecurityUtils.getSubject();
        UsernamePasswordToken token = new UsernamePasswordToken(user.getId().toString(), requestVo.getPassword());
        try {
            subject.login(token);
        } catch (AuthenticationException e) {
            throw new BusinessException(BusinessErrorEnum.WRONG_USERNAME_OR_PASSWORD);
        }

        captchaService.deleteCaptcha(requestVo.getVerifyCodeRandom());
        userService.updateUserLoginTimeAndIpByUserId(user.getId(), new Date(), IpUtil.getIpAddress());

        UserLoginResponseVo responseVo = mapperFacade.map(user, UserLoginResponseVo.class);

        // 设置权限对象
        UserRoleResponseVo roleVo = new UserRoleResponseVo();
        roleVo.setAdmin(user.getAdmin());
        roleVo.setSuperBoardAdmin(user.getSuperBoardAdmin());
        roleVo.setBoardAdmin(boardAdminService.countByUserId(user.getId()) > 0);
        roleVo.setCategoryAdmin(categoryAdminService.countByUserId(user.getId()) > 0);
        responseVo.setPermission(roleVo);

        return responseVo;
    }

    public void logout() {
        Subject subject = SecurityUtils.getSubject();
        subject.logout();
    }

    public void register(UserRegisterRequestVo requestVo) {
        User sameUsernameUser = userService.getNotDeletedUserByUsername(requestVo.getUsername());
        if (sameUsernameUser != null) {
            throw new BusinessException(BusinessErrorEnum.DUPLICATE_USERNAME);
        }
        User sameEmailUser = userService.getNotDeletedUserByEmail(requestVo.getEmail());
        if (sameEmailUser != null) {
            throw new BusinessException(BusinessErrorEnum.DUPLICATE_EMAIL);
        }

        User user = mapperFacade.map(requestVo, User.class);
        user.setPassword(PasswordUtil.encryptPasswordBcrypt(user.getPassword()));

        userService.addUserByRegister(user);
        permissionService.addOrUpdateForumPermission(Permission.getDefaultForumPermission(user.getId()));
        captchaService.deleteCaptcha(requestVo.getVerifyCodeRandom());
    }

    public void updatePersonalUserInfo(UpdatePersonalUserInfoRequestVo requestVo) {
        Subject subject = SecurityUtils.getSubject();
        if (subject.getPrincipal() == null) {
            throw new BusinessException(BusinessErrorEnum.USER_NOT_LOGIN);
        }
        Long userId = (Long)subject.getPrincipal();
        userService.updateUserPersonalInfoByUserId(userId, StringUtils.normalizeSpace(requestVo.getNickname()),
                SexEnum.fromValue(requestVo.getSex()), requestVo.getSignature());
    }

    public void updatePersonalPassword(UpdatePersonalPasswordRequestVo requestVo) {
        Subject subject = SecurityUtils.getSubject();
        if (subject.getPrincipal() == null) {
            throw new BusinessException(BusinessErrorEnum.USER_NOT_LOGIN);
        }
        Long userId = (Long)subject.getPrincipal();
        User currentUser = userService.getNotDeletedUserByUserId(userId);
        if (currentUser == null) {
            throw new BusinessException(BusinessErrorEnum.USER_NOT_LOGIN);
        }
        String correctOldPassword = currentUser.getPassword();
        boolean oldPasswordCorrect = PasswordUtil.checkPasswordBcrypt(requestVo.getOldPassword(), correctOldPassword);
        if (!oldPasswordCorrect) {
            throw new BusinessException(BusinessErrorEnum.WRONG_OLD_PASSWORD);
        }
        String encryptedPassword = PasswordUtil.encryptPasswordBcrypt(requestVo.getNewPassword());
        userService.updateUserPasswordByUserId(userId, encryptedPassword);
        // 删除该用户的其他会话
        SessionUtil.removeSessionsByUserId(userId, true);
        // 用新的密码重新登录
        UsernamePasswordToken token = new UsernamePasswordToken(userId.toString(), requestVo.getNewPassword());
        try {
            subject.login(token);
        } catch (AuthenticationException e) {
            // 如果报错可能是因为刚好用户被禁用或删除
            subject.logout();
            throw new BusinessException(BusinessErrorEnum.USER_NOT_LOGIN);
        }
        captchaService.deleteEmailVerifyCode(requestVo.getEmailVerifyCode());
    }

    public void deleteUser(Long userId) {
        Long currentUserId = (Long)SecurityUtils.getSubject().getPrincipal();
        if (userId.equals(currentUserId)) {
            throw new BusinessException(BusinessErrorEnum.CANT_DELETE_OWN_ACCOUNT);
        }
        User user = userService.getNotDeletedUserByUserId(userId);
        if (user == null) {
            throw new BusinessException(BusinessErrorEnum.USER_NOT_FOUND);
        }
        // 删除该用户的所有会话
        SessionUtil.removeSessionsByUserId(userId, false);
        userService.deleteUserByUserId(userId);

        // 删除该用户的所有主题帖
        topicService.batchCascadeDeleteTopic(userId, null, null);
        // 删除该用户的所有回复帖
        replyService.batchCascadeDeleteReply(userId, null, null, null);
        // 删除该用户的所有附件
        attachmentService.batchDeleteAttachmentByUploaderUserId(userId);
    }

    public List<UserSearchResponseVo> getUsernameList(String searchName) {
        List<User> userList = userService.listUsersByName(searchName);
        List<UserSearchResponseVo> responseVoList = mapperFacade.mapAsList(userList, UserSearchResponseVo.class);
        return responseVoList;
    }

    public void updateBoardAdmin(Long userId, List<Long> boardIdList) {
        User user = userService.getNotDeletedUserByUserId(userId);
        if (user == null) {
            throw new BusinessException(BusinessErrorEnum.USER_NOT_FOUND);
        }
        // 判断列表里的板块是否都存在
        Set<Long> notExistingBoardIdList = new HashSet<Long>(CollectionUtils.removeAll(boardIdList,
                boardService.listNotDeletedBoardIds()));
        log.debug("not exist {}", notExistingBoardIdList);
        if (!notExistingBoardIdList.isEmpty()) {
            throw new BusinessException(BusinessErrorEnum.BOARD_NOT_FOUND);
        }

        Set<Long> currentBoardIdList = boardAdminService.listBoardIdByUserId(userId);
        Set<Long> needToAdd = new HashSet<Long>(CollectionUtils.removeAll(boardIdList, currentBoardIdList));
        Set<Long> needToDelete = new HashSet<Long>(CollectionUtils.removeAll(currentBoardIdList, boardIdList));
        log.debug("old {}, after {}", currentBoardIdList, boardIdList);
        log.debug("need to add {}", needToAdd);
        log.debug("need to delete {}", needToDelete);
        boardAdminService.batchDeleteBoardAdminByUserId(userId, needToDelete);
        boardAdminService.batchAddBoardAdminByUserId(userId, needToAdd);
    }

    public void updateCategoryAdmin(Long userId, List<Long> categoryIdList) {
        User user = userService.getNotDeletedUserByUserId(userId);
        if (user == null) {
            throw new BusinessException(BusinessErrorEnum.USER_NOT_FOUND);
        }
        // 判断列表里的分区是否都存在
        Set<Long> notExistingCategoryIdList = new HashSet<Long>(CollectionUtils.removeAll(categoryIdList,
                categoryService.listNotDeletedCategoryIds()));
        log.debug("not exist {}", notExistingCategoryIdList);
        if (!notExistingCategoryIdList.isEmpty()) {
            throw new BusinessException(BusinessErrorEnum.CATEGORY_NOT_FOUND);
        }

        Set<Long> currentCategoryIdList = categoryAdminService.listCategoryIdByUserId(userId);
        Set<Long> needToAdd = new HashSet<Long>(CollectionUtils.removeAll(categoryIdList, currentCategoryIdList));
        Set<Long> needToDelete = new HashSet<Long>(CollectionUtils.removeAll(currentCategoryIdList, categoryIdList));
        log.debug("old {}, after {}", currentCategoryIdList, categoryIdList);
        log.debug("need to add {}", needToAdd);
        log.debug("need to delete {}", needToDelete);
        categoryAdminService.batchDeleteCategoryAdminByUserId(userId, needToDelete);
        categoryAdminService.batchAddCategoryAdminByUserId(userId, needToAdd);
    }

    public void updateForumPermission(UpdateForumPermissionRequestVo requestVo) {
        User user = userService.getNotDeletedUserByUserId(requestVo.getUserId());
        if (user == null) {
            throw new BusinessException(BusinessErrorEnum.USER_NOT_FOUND);
        }

        if (requestVo.getUserId().equals(SecurityUtils.getSubject().getPrincipal()) &&
                requestVo.getBanVisit()) {
            throw new BusinessException(BusinessErrorEnum.CANT_SET_OWN_ACCOUNT_BAN_LOGIN);
        }

        Permission permission = mapperFacade.map(requestVo, Permission.class);
        permissionService.addOrUpdateForumPermission(permission);
    }

    public void updateUserBoardPermission(UpdateBoardPermissionRequestVo requestVo) {
        User user = userService.getNotDeletedUserByUserId(requestVo.getUserId());
        if (user == null) {
            throw new BusinessException(BusinessErrorEnum.USER_NOT_FOUND);
        }
        Board board = boardService.getNotDeletedBoardByBoardId(requestVo.getBoardId());
        if (board == null) {
            throw new BusinessException(BusinessErrorEnum.BOARD_NOT_FOUND);
        }

        Permission permission = mapperFacade.map(requestVo, Permission.class);
        permissionService.addOrUpdateUserBoardPermission(permission);
    }

    public void updateUserInfo(UpdateUserInfoRequestVo requestVo) {
        User user = userService.getNotDeletedUserByUserId(requestVo.getId());
        if (user == null) {
            throw new BusinessException(BusinessErrorEnum.USER_NOT_FOUND);
        }
        User sameUsernameUser = userService.getNotDeletedUserByUsername(requestVo.getUsername());
        if (sameUsernameUser != null && !sameUsernameUser.getId().equals(user.getId())) {
            throw new BusinessException(BusinessErrorEnum.DUPLICATE_USERNAME);
        }
        User sameEmailUser = userService.getNotDeletedUserByEmail(requestVo.getEmail());
        if (sameEmailUser != null && !sameEmailUser.getId().equals(user.getId())) {
            throw new BusinessException(BusinessErrorEnum.DUPLICATE_EMAIL);
        }
        if (requestVo.getId().equals(SecurityUtils.getSubject().getPrincipal()) && !requestVo.getAdmin()) {
            throw new BusinessException(BusinessErrorEnum.CANT_SET_OWN_ACCOUNT_ADMIN);
        }

        boolean isRefreshSessionNeeded = !requestVo.getUsername().equals(user.getUsername()) ||
                !StringUtils.isEmpty(requestVo.getPassword());

        String plainPassword = requestVo.getPassword();

        if (!StringUtils.isEmpty(requestVo.getPassword())) {
            // 如果密码不为空的话就加密密码
            String encryptedPassword = PasswordUtil.encryptPasswordBcrypt(requestVo.getPassword());
            requestVo.setPassword(encryptedPassword);
        }

        boolean isEmailNeedToVerify = !requestVo.getEmail().equals(user.getEmail());
        if (isEmailNeedToVerify) {
            user.setEmailVerified(false);
        }

        requestVo.setNickname(StringUtils.normalizeSpace(requestVo.getNickname()));

        mapperFacade.map(requestVo, user);
        userService.updateUser(user);

        if (isRefreshSessionNeeded) {
            // 删除该用户的会话
            SessionUtil.removeSessionsByUserId(user.getId(), true);

            // 如果修改的是自己的帐号
            Subject subject = SecurityUtils.getSubject();
            if (requestVo.getId().equals(subject.getPrincipal())) {
                // 用新的密码重新登录
                UsernamePasswordToken token = new UsernamePasswordToken(user.getId().toString(), plainPassword);

                try {
                    subject.login(token);
                } catch (AuthenticationException e) {
                    // 如果报错可能是因为刚好用户被禁用或删除
                    subject.logout();
                    throw new BusinessException(BusinessErrorEnum.USER_NOT_LOGIN);
                }
            }
        }
    }

    public UserCenterInfoResponseVo getUserCenterInfo(Long userId) {
        User user = userService.getNotDeletedUserByUserId(userId);
        if (user == null) {
            throw new BusinessException(BusinessErrorEnum.USER_NOT_FOUND);
        }

        UserCenterInfoResponseVo responseVo = mapperFacade.map(user, UserCenterInfoResponseVo.class);

        Long currentUserId = (Long)SecurityUtils.getSubject().getPrincipal();
        // 如果不是自己的个人中心则隐藏邮箱和邮箱是否已激活
        if (!userId.equals(currentUserId)) {
            responseVo.setEmail(null);
            responseVo.setEmailVerified(null);
        }

        List<Board> boardAdminList = boardService.listNotDeletedAdminBoardsByUserId(userId);
        List<Category> categoryAdminList = categoryService.listNotDeletedAdminCategoriesByUserId(userId);

        responseVo.setBoardAdmin(mapperFacade.mapAsList(boardAdminList, IdNameResponseVo.class));
        responseVo.setCategoryAdmin(mapperFacade.mapAsList(categoryAdminList, IdNameResponseVo.class));

        return responseVo;
    }

    public void addUserByAdmin(AddUserRequestVo requestVo) {
        User sameUsernameUser = userService.getNotDeletedUserByUsername(requestVo.getUsername());
        if (sameUsernameUser != null) {
            throw new BusinessException(BusinessErrorEnum.DUPLICATE_USERNAME);
        }
        User sameEmailUser = userService.getNotDeletedUserByEmail(requestVo.getEmail());
        if (sameEmailUser != null) {
            throw new BusinessException(BusinessErrorEnum.DUPLICATE_EMAIL);
        }

        String plainPassword = requestVo.getPassword();
        requestVo.setPassword(PasswordUtil.encryptPasswordBcrypt(plainPassword));
        User user = mapperFacade.map(requestVo, User.class);

        Permission forumPermission = mapperFacade.map(requestVo.getForumPermission(), Permission.class);
        userService.addUserByAdmin(user);
        Long userId = user.getId();

        forumPermission.setUserId(userId);
        permissionService.addOrUpdateForumPermission(forumPermission);
    }

    public PageResultVo<UserResponseVo> getUserList(Long userId, String username, UserTypeEnum userTypeEnum, long pageIndex, long pageSize) {
        User user = userService.getCurrentLoginUser();
        if (!user.getAdmin() && !user.getId().equals(userId)) {
            throw new BusinessException(BusinessErrorEnum.UNAUTHORIZED);
        }

        Page<User> page = new Page<>(pageIndex, pageSize);
        Page<User> pageResult = userService.listNotDeletedUsersByPageAndUsernameAndType(page, userId, userTypeEnum, username);

        List<UserResponseVo> responseVoList = mapperFacade.mapAsList(pageResult.getRecords(), UserResponseVo.class);
        responseVoList.forEach(item -> {
            Long loopUserId = item.getId();

            // 设置版主和分区版主
            List<Board> boardAdminList = boardService.listNotDeletedAdminBoardsByUserId(loopUserId);
            List<Category> categoryAdminList = categoryService.listNotDeletedAdminCategoriesByUserId(loopUserId);

            item.setBoardAdmin(mapperFacade.mapAsList(boardAdminList, BoardResponseVo.class));
            item.setCategoryAdmin(mapperFacade.mapAsList(categoryAdminList, CategoryResponseVo.class));

            // 设置论坛权限
            Permission forumPermission = permissionService.getForumPermissionByUserId(loopUserId);
            item.setForumPermission(mapperFacade.map(forumPermission, PermissionResponseVo.class));

            // 设置板块权限
            List<Permission> boardPermissionList = permissionService.listBoardPermissionsByUserId(loopUserId);
            List<Long> boardIdList = boardPermissionList.stream()
                    .map(Permission::getBoardId)
                    .collect(Collectors.toList());
            Map<Long, Board> boardMap = boardService.batchListNotDeletedBoardsByBoardIds(boardIdList)
                    .stream()
                    .collect(Collectors.toMap(Board::getId, Function.identity()));

            List<IdNamePermissionResponseVo> boardPermissionVoList = mapperFacade.mapAsList(boardPermissionList, IdNamePermissionResponseVo.class);

            for (int i = 0; i < boardPermissionVoList.size(); i++) {
                Long boardId = boardPermissionList.get(i).getBoardId();
                boardPermissionVoList.get(i).setId(boardId);
                boardPermissionVoList.get(i).setName(boardMap.get(boardId).getName());
            }
            item.setBoardPermission(boardPermissionVoList);

            // 设置用户类型
            UserTypeEnum responseUserType;
            if (item.getAdmin()) {
                responseUserType = UserTypeEnum.ADMIN;
            } else if (item.getSuperBoardAdmin()) {
                responseUserType = UserTypeEnum.SUPER_BOARD_ADMIN;
            } else if (!item.getCategoryAdmin().isEmpty()) {
                responseUserType = UserTypeEnum.CATEGORY_ADMIN;
            } else if (!item.getBoardAdmin().isEmpty()) {
                responseUserType = UserTypeEnum.BOARD_ADMIN;
            } else {
                responseUserType = UserTypeEnum.NORMAL_USER;
            }
            item.setUserType(responseUserType.getName());
        });
        return new PageResultVo<>(responseVoList, pageResult);
    }

    public UserBoardPermissionResponseVo getUserBoardPermission(Long boardId) {
        Long userId = (Long)SecurityUtils.getSubject().getPrincipal();
        User user = userService.getNotDeletedUserByUserId(userId);

        // 如果没登录，则直接返回false，否则根据用户是否为超级版主或管理员和管理的板块、分区判断
        boolean isBoardAdmin = user != null &&
                (user.getSuperBoardAdmin() || user.getAdmin() ||
                        boardService.checkBoardAdminByUserIdAndBoardId(userId, boardId));

        Board board = boardService.getNotDeletedBoardByBoardId(boardId);
        if (board == null) {
            UserBoardPermissionResponseVo responseVo = new UserBoardPermissionResponseVo();
            responseVo.setBoardAdmin(false);
            responseVo.setBanVisit(true);
            responseVo.setBanCreateTopic(true);
            responseVo.setBanReply(true);
            responseVo.setBanUploadAttachment(true);
            responseVo.setBanDownloadAttachment(true);
            return responseVo;
        }
        Category category = categoryService.getNotDeletedCategoryByCategoryId(board.getCategoryId());

        boolean boardVisible = board.getVisible() && category.getVisible() || isBoardAdmin;

        // 如果用户未登录则直接根据板块是否可见返回结果
        if (user == null) {
            UserBoardPermissionResponseVo responseVo = new UserBoardPermissionResponseVo();
            responseVo.setBoardAdmin(false);
            responseVo.setBanVisit(!boardVisible);
            responseVo.setBanCreateTopic(true);
            responseVo.setBanReply(true);
            responseVo.setBanUploadAttachment(true);
            responseVo.setBanDownloadAttachment(true);
            return responseVo;
        }

        Permission forumPermission = Optional.ofNullable(permissionService.getForumPermissionByUserId(userId))
                .orElse(Permission.getDefaultPermission());
        Permission boardPermission = Optional.ofNullable(permissionService.getBoardPermissionByBoardId(boardId))
                .orElse(Permission.getDefaultPermission());
        Permission userBoardPermission = Optional.ofNullable(permissionService.getUserBoardPermissionByUserIdAndBoardId(userId, boardId))
                .orElse(Permission.getDefaultPermission());

        // 如果板块是隐藏的则禁止访问
        boolean banVisit = !boardVisible || forumPermission.getBanVisit() ||
                (!isBoardAdmin && boardPermission.getBanVisit()) || userBoardPermission.getBanVisit();
        boolean banCreateTopic = banVisit || (!isBoardAdmin && boardPermission.getBanCreateTopic()) ||
                forumPermission.getBanCreateTopic() || userBoardPermission.getBanCreateTopic();
        boolean banReply = banVisit || (!isBoardAdmin && boardPermission.getBanReply()) ||
                forumPermission.getBanReply() || userBoardPermission.getBanReply();
        boolean banUploadAttachment = banVisit || (!isBoardAdmin && boardPermission.getBanUploadAttachment()) ||
                forumPermission.getBanUploadAttachment() || userBoardPermission.getBanUploadAttachment();
        boolean banDownloadAttachment = banVisit || (!isBoardAdmin && boardPermission.getBanDownloadAttachment()) ||
                forumPermission.getBanDownloadAttachment() || userBoardPermission.getBanDownloadAttachment();

        UserBoardPermissionResponseVo responseVo = new UserBoardPermissionResponseVo();
        responseVo.setBoardAdmin(isBoardAdmin);
        responseVo.setBanVisit(banVisit);
        responseVo.setBanCreateTopic(banCreateTopic);
        responseVo.setBanReply(banReply);
        responseVo.setBanUploadAttachment(banUploadAttachment);
        responseVo.setBanDownloadAttachment(banDownloadAttachment);

        log.debug("forumPermission = {}", forumPermission);
        log.debug("boardPermission = {}", boardPermission);
        log.debug("userBoardPermission = {}", userBoardPermission);
        log.debug("用户：{}，板块：{}，权限：{}", userId, boardId, responseVo);
        return responseVo;
    }

    public AvatarPathResponseVo updateUserAvatar(MultipartFile multipartFile) throws IOException {
        String filename = UUID.randomUUID(true).toString(true);

        File tempFile = File.createTempFile(Constant.TEMP_FILE_PREFIX, null);

        multipartFile.transferTo(tempFile);

        Tika tika = new Tika();
        String fileType = tika.detect(tempFile);

        if (!ArrayUtils.contains(Constant.ALLOWED_AVATAR_MEDIA_TYPES, fileType)) {
            throw new BusinessException(BusinessErrorEnum.UPLOAD_FILE_TYPE_NOT_ALLOWED);
        }

        BufferedImage image = ImgUtil.read(tempFile);

        Pair<Integer, Integer> imageSize = new ImmutablePair<>(image.getWidth(), image.getHeight());
        if (imageSize.compareTo(Constant.AVATAR_MIN_SIZE) < 0 || imageSize.compareTo(Constant.AVATAR_MAX_SIZE) > 0 ||
                image.getHeight() != image.getWidth()) {
            throw new BusinessException(BusinessErrorEnum.WRONG_AVATAR_SIZE);
        }

        File file = Constant.AVATAR_PATH.resolve(filename + ".png").toFile();
        ImgUtil.convert(tempFile, file);

        tempFile.delete();

        Long userId = (Long)SecurityUtils.getSubject().getPrincipal();
        String avatarPath = Constant.AVATAR_API_ENDPOINT + file.getName();

        userService.updateUserAvatarByUserId(userId, avatarPath);
        return new AvatarPathResponseVo(avatarPath);
    }

    public void sendEmailVerifyCode(SendEmailVerifyCodeRequestVo requestVo) {
        String email = requestVo.getEmail();
        // 如果邮箱为null则为当前用户登录的邮箱
        if (email == null) {
            User user = userService.getCurrentLoginUser();
            email = user.getEmail();
        }
        String emailVerifyCode = captchaService.getEmailVerifyCode();
        captchaService.storeEmailVerifyCodeToRedis(email, emailVerifyCode);
        captchaService.deleteCaptcha(requestVo.getVerifyCodeRandom());
        String text = "邮箱验证码为：" + emailVerifyCode + "，五分钟内有效";
        emailService.sendEmail(emailSender, email,
                "论坛系统邮箱验证码", text);
    }

    public void resetPassword(UserResetPasswordRequestVo requestVo) {
        User user = userService.getNotDeletedUserByEmail(requestVo.getEmail());
        if (user == null) {
            throw new BusinessException(BusinessErrorEnum.USER_NOT_FOUND);
        }
        String encryptedPassword = PasswordUtil.encryptPasswordBcrypt(requestVo.getNewPassword());
        userService.updateUserPasswordByUserId(user.getId(), encryptedPassword);
        SessionUtil.removeSessionsByUserId(user.getId(), true);
        captchaService.deleteEmailVerifyCode(requestVo.getEmail());
    }

    public void verifyEmail(UserVerifyEmailRequestVo requestVo) {
        User user = userService.getCurrentLoginUser();
        userService.updateUserEmailVerifiedByUserId(user.getId());
        captchaService.deleteEmailVerifyCode(user.getEmail());
    }

    public void updateEmail(UserUpdateEmailRequestVo requestVo) {
        User user = userService.getCurrentLoginUser();
        if (!PasswordUtil.checkPasswordBcrypt(requestVo.getPassword(), user.getPassword())) {
            throw new BusinessException(BusinessErrorEnum.WRONG_OLD_PASSWORD);
        }

        userService.updateUserEmailByUserId(user.getId(), requestVo.getEmail());
        userService.updateUserEmailVerifiedByUserId(user.getId());
        captchaService.deleteEmailVerifyCode(requestVo.getEmail());
    }

}
