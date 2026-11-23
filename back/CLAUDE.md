# back/CLAUDE.md

## Trampas de los tests y de Flyway

* **El host del SMTP de los tests va en `application.properties` de test**, no
  en el `DynamicPropertyRegistrar`: la autoconfiguracion del `JavaMailSender`
  mira esa propiedad antes de que los contenedores registren las suyas.
* **`src/test/resources/application.properties` reemplaza al principal**, no
  se suma: cualquier propiedad sin valor por defecto (p. ej. el placeholder
  `appPassword` de Flyway) hay que repetirla ahi.
* **Flyway sustituye placeholders tambien en los comentarios SQL**: no
  escribas `${...}` en un comentario de una migracion.

## Trampas de Spring Data JDBC

* **No actives `setSingleQueryLoadingEnabled(true)`** (4.1.1). El SQL que
  genera no ordena las filas y el lector supone que las de un agregado llegan
  juntas: con la base casi vacia pasa, con datos reales Postgres cambia de plan
  y devuelve solicitudes con todas las columnas en null salvo id y lineas.
* **Una consulta derivada que devuelve agregados con coleccion es N+1**, y una
  proyeccion DTO tampoco lo evita (lee la entidad y despues proyecta). Los
  listados de solicitudes van por `ListadoSolicitudes` (otra entidad sobre la
  tabla, sin lineas). `RepositorioSolicitudesTest` cuenta las sentencias.
* **Nada de `save()` para actualizar**: con una clave ya asignada Spring Data
  hace `UPDATE` de TODAS las columnas (y borra y reinserta las colecciones), y
  `biblioteca_app` solo tiene `UPDATE` de columnas concretas. Los cambios son
  `@Modifying @Query`; las altas, `save()` con id null (`GeneradorIdSolicitud`)
  o `Persistable.isNew() = true`.
* **`UPDATE ... RETURNING` va sin `@Modifying`**: devuelve filas y se ejecuta
  como consulta (ver `RepositorioLibros#sumar`).
