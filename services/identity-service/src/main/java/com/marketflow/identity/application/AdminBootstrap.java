package com.marketflow.identity.application;

import com.marketflow.identity.config.IdentityProperties;
import com.marketflow.identity.domain.User;
import com.marketflow.identity.persistence.UserRepository;
import com.marketflow.platform.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates the first administrator from configuration (MARKETFLOW_BOOTSTRAP_ADMIN_EMAIL / _PASSWORD) when no
 * admin exists yet. There is deliberately no API through which anybody can become ADMIN, and no password hash
 * is committed to source control.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final IdentityProperties props;
    private final TransactionTemplate tx;

    public AdminBootstrap(UserRepository users, PasswordEncoder encoder, IdentityProperties props,
                          TransactionTemplate tx) {
        this.users = users;
        this.encoder = encoder;
        this.props = props;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        IdentityProperties.BootstrapAdmin cfg = props.bootstrapAdmin();
        if (cfg == null || cfg.email() == null || cfg.email().isBlank() || cfg.password() == null
                || cfg.password().isBlank()) {
            return;
        }
        tx.executeWithoutResult(status -> {
            if (users.existsWithRole(Role.ADMIN)) {
                return;
            }
            User admin = users.findByEmail(cfg.email()).orElseGet(() -> users.save(User.register(cfg.email(),
                    encoder.encode(cfg.password()), cfg.fullName() == null ? "Platform Admin" : cfg.fullName())));
            admin.grant(Role.ADMIN);
            log.info("Bootstrap administrator account ensured (id={})", admin.getId());
        });
    }
}
