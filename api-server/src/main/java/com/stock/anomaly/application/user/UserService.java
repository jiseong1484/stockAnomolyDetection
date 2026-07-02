package com.stock.anomaly.application.user;

import com.stock.anomaly.domain.user.User;
import com.stock.anomaly.domain.user.UserRepository;
import com.stock.anomaly.infrastructure.external.kis.KisTokenManager;
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
    private final KisTokenManager kisTokenManager;

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
        if (!kisTokenManager.validateKeys(request.getKisApiKey(), request.getKisSecretKey())) {
            throw new IllegalArgumentException("유효하지 않은 KIS API 키입니다. 발급받은 키를 다시 확인해주세요.");
        }
        user.updateKisKeys(request.getKisApiKey(), request.getKisSecretKey());
        userRepository.save(user);
    }

    public void signUp(SignUpRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("이미 사용 중인 이메일입니다.");
        }

        String kisApiKey = request.getKisApiKey();
        String kisSecretKey = request.getKisSecretKey();
        boolean hasKisKeys = kisApiKey != null && !kisApiKey.isBlank()
                && kisSecretKey != null && !kisSecretKey.isBlank();

        if (hasKisKeys && !kisTokenManager.validateKeys(kisApiKey, kisSecretKey)) {
            throw new IllegalArgumentException("유효하지 않은 KIS API 키입니다. 발급받은 키를 다시 확인해주세요.");
        }

        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .name(request.getName())
                .phoneNumber(request.getPhoneNumber())
                .kisApiKey(hasKisKeys ? kisApiKey : null)
                .kisSecretKey(hasKisKeys ? kisSecretKey : null)
                .build();

        userRepository.save(user);
    }
}
