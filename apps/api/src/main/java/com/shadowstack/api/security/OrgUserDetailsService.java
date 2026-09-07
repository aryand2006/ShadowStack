package com.shadowstack.api.security;

import com.shadowstack.api.persistence.PersistedUser;
import com.shadowstack.api.persistence.UserRepository;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Loads users from {@code ss_users} when present, falling back to env credentials
 * from {@link ProdUsersConfig} so bootstrap and empty-table startups still authenticate.
 */
@Service
@Primary
@Profile("!demo")
public class OrgUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final ProdUsersConfig.EnvCredentialUsers envCredentialUsers;

    public OrgUserDetailsService(
            UserRepository userRepository,
            ProdUsersConfig.EnvCredentialUsers envCredentialUsers) {
        this.userRepository = userRepository;
        this.envCredentialUsers = envCredentialUsers;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        Optional<PersistedUser> fromDb = userRepository.findByUsernameIgnoreCase(username);
        if (fromDb.isPresent()) {
            return OrgUserDetails.fromPersisted(fromDb.get());
        }
        return envCredentialUsers.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }
}
