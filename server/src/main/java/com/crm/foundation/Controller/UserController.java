package com.crm.foundation.Controller;

import com.crm.foundation.Domain.User;
import com.crm.foundation.Service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Optional;
import java.util.UUID;

@Controller("user")
public class UserController {

    @Autowired
    public UserService userService;

    @GetMapping(path = "/id")
    public Optional<User> GetUserById(@NonNull UUID id) {
        return userService.findById(id);
    }
}
