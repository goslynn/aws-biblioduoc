package cl.duoc.biblioteca.config;

import jakarta.servlet.DispatcherType;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import cl.duoc.biblioteca.comun.seguridad.Roles;
import cl.duoc.biblioteca.comun.seguridad.ValidacionJwt;

/**
 * LA SEGUNDA CAPA DE SEGURIDAD (Defense in Depth).
 *
 * API Gateway ya valida el token antes de reenviar la peticion a la EC2. ¿Por
 * que volver a validarlo aqui? Porque API Gateway no es el unico camino
 * posible hacia la aplicacion:
 *
 *   * cualquiera dentro de la VPC (la VPN opcional, por ejemplo) llega al
 *     puerto 8080 de la EC2 sin pasar por el gateway,
 *   * manana, otro servicio de la cuenta llamando al NLB interno,
 *   * o la misma aplicacion desplegada en otro sitio.
 *
 * Con esta clase, ninguna de esas vias entrega datos sin un JWT valido. Sin
 * ella, cualquiera que llegue a la EC2 se salta el authorizer entero.
 *
 * Y es la UNICA capa que conoce los roles: API Gateway solo comprueba que el
 * token traiga alguno de los scopes de la API. Quien es empleado lo decide
 * {@link ValidacionJwt#autoridades()}, en el modulo comun: el administrador
 * de RabbitMQ aplica exactamente la misma regla.
 *
 * QUE VALIDA, EN ORDEN
 *   1. Firma, emisor y expiracion   -> JwtDecoder de ValidacionJwt, contra las
 *      claves publicas (JWKS) que el pipeline entrega en BIBLIOTECA_JWKS.
 *   2. token_use == "access"        -> validador propio de ValidacionJwt.
 *   3. El scope exigido             -> hasAuthority("SCOPE_<scope>").
 *   4. El rol, en /api/gestion/**    -> hasRole("EMPLEADO").
 *
 * Aqui quedan solo las reglas de RUTA de esta API y su CORS: el resto es
 * comun a los microservicios.
 */
@Configuration
public class SecurityConfig {

    /** Inyectado por BIBLIOTECA_ORIGEN: el origen del sitio (Caddy). */
    @Value("${biblioteca.origen}")
    private String origenWeb;

    /** Decodificador, autoridades y rol empleado: los mismos en todos los servicios. */
    private final ValidacionJwt validacion;

    public SecurityConfig(ValidacionJwt validacion) {
        this.validacion = validacion;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // ---------------------------------------------------
                        //  EL DESPACHO DE ERROR, que cuesta una tarde entera.
                        //
                        //  Cuando un controlador lanza una excepcion —por
                        //  ejemplo el ResponseStatusException(404) de un id que
                        //  no existe— el contenedor reenvia internamente a
                        //  /error para construir la respuesta. Ese reenvio pasa
                        //  OTRA VEZ por estas reglas, con dispatcher type ERROR.
                        //
                        //  /error no encaja en "/api/**", asi que caia en el
                        //  denyAll() del final y el 404 salia convertido en un
                        //  403. Medido: GET /api/libros/L-999 devolvia 403 con
                        //  cuerpo vacio, tanto por API Gateway como llamando a
                        //  la Lambda directamente.
                        //
                        //  Permitir el dispatcher ERROR no abre nada: no se
                        //  puede llegar a /error desde fuera, solo por reenvio
                        //  interno despues de una peticion YA autorizada.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()

                        // Health check del NLB. No se publica en API Gateway
                        // y no revela nada. Ver SaludController.
                        .requestMatchers("/health").permitAll()

                        // El preflight del navegador viaja SIN Authorization:
                        // si se le exigiera token, el navegador cancelaria la
                        // peticion real y el error se veria como un problema
                        // de CORS en vez de como lo que es.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // RBAC: la gestion (aprobar, rechazar, devolver, cargar
                        // stock, bitacora) es solo de empleados. Va ANTES de la
                        // regla general: Spring usa la primera que encaja.
                        .requestMatchers("/api/gestion/**", "/v1/api/gestion/**")
                        .hasRole(Roles.EMPLEADO)

                        // La regla estricta: no basta con estar autenticado,
                        // hay que traer el scope. Es lo que separa el 401 del
                        // 403 tambien dentro de la aplicacion. El backoffice no
                        // trae el scope de acceso, pero es empleado: tambien
                        // puede leer el catalogo que esta gestionando.
                        .requestMatchers("/api/**", "/v1/api/**")
                        .hasAnyAuthority(validacion.autoridadAcceso(), Roles.AUTORIDAD_EMPLEADO)

                        .anyRequest().denyAll())

                // Se valida el JWT en cada peticion; no se crea sesion.
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(validacion.convertidor())))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // CSRF protege formularios con sesion y cookies. Aqui no hay
                // ni sesion ni cookies: la credencial es un Bearer que el
                // navegador no adjunta solo, asi que no hay nada que falsear.
                .csrf(csrf -> csrf.disable())

                .cors(cors -> cors.configurationSource(corsConfigurationSource()));

        return http.build();
    }

    /**
     * CORS de las respuestas REALES.
     *
     * Con integracion HTTP_PROXY, API Gateway devuelve exactamente lo que
     * responde la EC2: no anade cabeceras. Asi que el preflight lo responde el
     * gateway (metodos OPTIONS con MOCK, en api.yaml) y la cabecera de la
     * respuesta 200 la pone Spring, aqui.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // Origen exacto, no "*": con "*" el navegador no permite enviar
        // credenciales y ademas se pierde el control de quien consume la API.
        config.setAllowedOrigins(List.of(origenWeb));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setMaxAge(600L);

        UrlBasedCorsConfigurationSource fuente = new UrlBasedCorsConfigurationSource();
        fuente.registerCorsConfiguration("/**", config);
        return fuente;
    }
}
