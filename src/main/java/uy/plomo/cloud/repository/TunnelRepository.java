package uy.plomo.cloud.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.plomo.cloud.entity.Tunnel;

import java.util.List;
import java.util.Optional;

public interface TunnelRepository extends JpaRepository<Tunnel, String> {

    List<Tunnel> findAllByGatewayId(String gatewayId);

    /** Verifica que el tunnel pertenezca al gateway indicado (previene acceso cruzado) */
    Optional<Tunnel> findByIdAndGatewayId(String id, String gatewayId);

    /** Todos los tunnels que tienen un puerto asignado (para reconstruir PortPool al reiniciar) */
    @Query("SELECT t FROM Tunnel t WHERE t.assignedPort IS NOT NULL")
    List<Tunnel> findAllWithAssignedPort();
}
