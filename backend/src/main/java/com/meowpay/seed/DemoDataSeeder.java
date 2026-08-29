package com.meowpay.seed;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.meowpay.auth.AuthService;
import com.meowpay.cat.CatRepository;
import com.meowpay.error.ApiException;

@Component
public class DemoDataSeeder implements ApplicationRunner {

    static final String DEMO_PASSWORD = "treats123";

    private final CatRepository catRepository;
    private final AuthService authService;

    public DemoDataSeeder(CatRepository catRepository, AuthService authService) {
        this.catRepository = catRepository;
        this.authService = authService;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed("luna");
        seed("milo");
        seed("whiskers");
    }

    private void seed(String username) {
        if (catRepository.existsByUsername(username)) {
            return;
        }
        try {
            authService.register(username, DEMO_PASSWORD);
        } catch (ApiException ignored) {
            // Unique race on a parallel boot — already there.
        }
    }
}
