package uy.plomo.cloud.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.plomo.cloud.entity.Gateway;

import java.util.List;
import java.util.Optional;

public interface GatewayRepository extends JpaRepository<Gateway, String> {

    /** Carga tunnels en el mismo query para evitar N+1 */
    @Query("SELECT g FROM Gateway g LEFT JOIN FETCH g.tunnels WHERE g.id = :id")
    Optional<Gateway> findByIdWithTunnels(@Param("id") String id);

    /** Todos los gateways de un usuario con sus tunnels — para el panel de admin */
    @Query("SELECT g FROM Gateway g LEFT JOIN FETCH g.tunnels WHERE g.owner.username = :username")
    List<Gateway> findAllByOwnerUsernameWithTunnels(@Param("username") String username);
}
