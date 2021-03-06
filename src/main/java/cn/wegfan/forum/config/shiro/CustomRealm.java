package cn.wegfan.forum.config.shiro;

import cn.wegfan.forum.constant.Constant;
import cn.wegfan.forum.model.entity.User;
import cn.wegfan.forum.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.subject.PrincipalCollection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CustomRealm extends AuthorizingRealm {

    @Autowired
    private UserService userService;

    public CustomRealm() {
        super();
        setCredentialsMatcher(new BcryptCredentialsMatcher());
        setCachingEnabled(false);
        setAuthenticationCachingEnabled(false);
        setAuthorizationCachingEnabled(false);
    }

    @Override
    public String getName() {
        return "CustomRealm";
    }

    @Override
    public AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
        User user = userService.getCurrentLoginUser();
        if (user == null) {
            return null;
        }
        SimpleAuthorizationInfo info = new SimpleAuthorizationInfo();
        if (user.getAdmin()) {
            info.addStringPermission(Constant.SHIRO_PERMISSION_ADMIN);
        }
        return info;
    }

    @Override
    public AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) throws AuthenticationException {
        String userIdString = (String)token.getPrincipal();
        if (userIdString == null) {
            return null;
        }
        Long userId = Long.parseLong(userIdString);
        User user = userService.getNotDeletedUserByUserId(userId);
        if (user == null) {
            return null;
        }
        String correctPassword = user.getPassword();
        SimpleAuthenticationInfo authenticationInfo = new SimpleAuthenticationInfo(userId, correctPassword, getName());
        return authenticationInfo;
    }

}
