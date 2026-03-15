package uy.plomo.cloud.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.plomo.cloud.entity.User;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    /** JOIN FETCH para evitar N+1 al acceder a user.getGateways() */
    @Query("SELECT u FROM User u LEFT JOIN FETCH u.gateways WHERE u.username = :username")
    Optional<User> findByUsernameWithGateways(@Param("username") String username);
}
