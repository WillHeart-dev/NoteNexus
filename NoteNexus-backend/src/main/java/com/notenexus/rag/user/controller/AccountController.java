package com.notenexus.rag.user.controller;

import com.notenexus.rag.auth.CurrentUserService;
import com.notenexus.rag.common.api.ApiResponse;
import com.notenexus.rag.common.log.OperationLog;
import com.notenexus.rag.user.model.dto.ChangePasswordRequest;
import com.notenexus.rag.user.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人账户控制器，提供修改密码等接口。
 */
@OperationLog
@RestController
@RequestMapping("/api/account")
public class AccountController {

    private final AccountService accountService;
    private final CurrentUserService currentUserService;

    public AccountController(AccountService accountService, CurrentUserService currentUserService) {
        this.accountService = accountService;
        this.currentUserService = currentUserService;
    }

    /** 修改当前登录用户的密码 */
    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        accountService.changePassword(currentUserService.getRequiredCurrentUser(), request);
        return ApiResponse.success(null);
    }
}
