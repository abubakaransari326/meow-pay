package com.meowpay.auth;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Bodies are accepted so malformed JSON maps to VALIDATION. Register/login land in later steps. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @PostMapping("/register")
    public void register(@RequestBody AuthDtos.AuthRequest request) {
    }

    @PostMapping("/login")
    public void login(@RequestBody AuthDtos.AuthRequest request) {
    }
}
