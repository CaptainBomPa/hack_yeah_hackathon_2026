package pl.hackyeah.controllayer.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByLogin(String login);

    /** Liczba kont per rola — polityka nie może usunąć roli, którą ktoś ma (PolicyValidator). */
    @Query("select u.role as role, count(u) as count from AppUser u group by u.role")
    List<RoleCount> countByRole();

    interface RoleCount {
        String getRole();

        Long getCount();
    }
}
