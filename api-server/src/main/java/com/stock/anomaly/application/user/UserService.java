package com.stock.anomaly.application.user;

import com.stock.anomaly.domain.user.User;
import com.stock.anomaly.domain.user.UserRepository;
import com.stock.anomaly.web.user.dto.SignUpRequest;
import com.stock.anomaly.web.user.dto.UpdateKisKeysRequest;
import com.stock.anomaly.web.user.dto.UserProfileResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(User user) {
        return UserProfileResponse.builder()
                .email(user.getEmail())
                .name(user.getName())
                .phoneNumber(user.getPhoneNumber())
                .hasKisApiKey(user.getKisApiKey() != null && !user.getKisApiKey().isBlank())
                .build();
    }

    public void updateKisKeys(User user, UpdateKisKeysRequest request) {
        user.updateKisKeys(request.getKisApiKey(), request.getKisSecretKey());
        userRepository.save(user);
    }

    public void signUp(SignUpRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email already exists: " + request.getEmail());
        }

        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .name(request.getName())
                .phoneNumber(request.getPhoneNumber())
                .kisApiKey(request.getKisApiKey())
                .kisSecretKey(request.getKisSecretKey())
                .build();

        userRepository.save(user);
    }
}
