-- =============================================================================
--  V2  --  SEED: el catalogo inicial de la biblioteca
-- =============================================================================
--
--  Son los 20 libros que antes vivian en src/main/resources/libros.json. Ese
--  archivo se convirtio UNA vez a este script (con jq) y se borro: asi el
--  catalogo tiene una sola fuente de verdad.
--
--  Lo aplica Flyway al arrancar, desde dentro de la VPC. No hay otra forma de
--  llegar a la base: es privada y no hay bastion, a proposito.
--
--  Flyway lo ejecuta UNA sola vez por base (queda anotado en
--  flyway_schema_history). Para anadir o corregir libros se escribe una V3, no
--  se edita este archivo: cambiarlo hace fallar la validacion del checksum.
-- =============================================================================

INSERT INTO libro (id, titulo, autor, genero, anio, isbn, ejemplares, ubicacion, sinopsis) VALUES
  ('L-001', 'Clean Code', 'Robert C. Martin', 'Informatica', 2008, '978-0132350884', 4, 'Estante A-1', 'Manual de estilo y artesania para escribir codigo que otras personas puedan leer.'),
  ('L-002', 'El Programador Pragmatico', 'Andrew Hunt y David Thomas', 'Informatica', 2019, '978-0135957059', 3, 'Estante A-1', 'Practicas de oficio para pasar de escribir codigo a construir software.'),
  ('L-003', 'Refactoring', 'Martin Fowler', 'Informatica', 2018, '978-0134757599', 2, 'Estante A-2', 'Catalogo de transformaciones para mejorar un diseno sin cambiar su comportamiento.'),
  ('L-004', 'Designing Data-Intensive Applications', 'Martin Kleppmann', 'Informatica', 2017, '978-1449373320', 2, 'Estante A-2', 'Como se comportan de verdad los sistemas distribuidos, las bases de datos y las colas.'),
  ('L-005', 'Building Microservices', 'Sam Newman', 'Informatica', 2021, '978-1492034025', 0, 'Estante A-3', 'Cuando partir un monolito, como hacerlo y que problemas nuevos se compran al hacerlo.'),
  ('L-006', 'Spring in Action', 'Craig Walls', 'Informatica', 2022, '978-1617297571', 5, 'Estante A-3', 'Recorrido practico por Spring Boot, MVC, seguridad y acceso a datos.'),
  ('L-007', 'OAuth 2 in Action', 'Justin Richer y Antonio Sanso', 'Seguridad', 2017, '978-1617293276', 2, 'Estante B-1', 'Que resuelve OAuth 2.0, que no resuelve, y como se rompe cuando se implementa a medias.'),
  ('L-008', 'Web Application Security', 'Andrew Hoffman', 'Seguridad', 2020, '978-1492053118', 1, 'Estante B-1', 'Reconocimiento, ataque y defensa de aplicaciones web modernas.'),
  ('L-009', 'Cloud Native Patterns', 'Cornelia Davis', 'Cloud', 2019, '978-1617294297', 3, 'Estante B-2', 'Patrones para aplicaciones que asumen que la infraestructura falla todo el tiempo.'),
  ('L-010', 'Serverless Architectures on AWS', 'Peter Sbarski', 'Cloud', 2022, '978-1617295423', 2, 'Estante B-2', 'Lambda, API Gateway y S3 como piezas de arquitectura, no como servicios sueltos.'),
  ('L-011', 'Terraform Up and Running', 'Yevgeniy Brikman', 'Cloud', 2022, '978-1098116743', 0, 'Estante B-3', 'Infraestructura como codigo: modulos, estado y despliegues reproducibles.'),
  ('L-012', 'Angular Development with TypeScript', 'Yakov Fain y Anton Moiseev', 'Informatica', 2018, '978-1617295348', 3, 'Estante A-4', 'Componentes, servicios, enrutamiento e interceptores explicados de punta a cabo.'),
  ('L-013', 'Estructuras de Datos y Algoritmos en Java', 'Michael T. Goodrich', 'Informatica', 2014, '978-1118771334', 6, 'Estante A-5', 'Texto base de la asignatura: listas, arboles, grafos y su costo real.'),
  ('L-014', 'Metodologia de la Investigacion', 'Roberto Hernandez Sampieri', 'Metodologia', 2014, '978-1456223960', 8, 'Estante C-1', 'El manual que piden en casi todos los proyectos de titulo.'),
  ('L-015', 'Como Escribir Bien en Espanol', 'Graciela Reyes', 'Lenguaje', 2018, '978-8476353424', 4, 'Estante C-2', 'Redaccion academica: parrafo, conector, cita y puntuacion.'),
  ('L-016', 'La Ciudad y los Perros', 'Mario Vargas Llosa', 'Literatura', 1963, '978-8420471839', 3, 'Estante D-1', 'El internado militar como retrato de un pais entero.'),
  ('L-017', 'Cien Anos de Soledad', 'Gabriel Garcia Marquez', 'Literatura', 1967, '978-0307474728', 2, 'Estante D-1', 'La saga de los Buendia en Macondo.'),
  ('L-018', 'Poemas y Antipoemas', 'Nicanor Parra', 'Literatura', 1954, '978-9561604315', 1, 'Estante D-2', 'La antipoesia chilena en su primera forma.'),
  ('L-019', 'Historia Contemporanea de Chile', 'Gabriel Salazar y Julio Pinto', 'Historia', 1999, '978-9562822404', 2, 'Estante D-3', 'Estado, sociedad y economia chilena del siglo XX.'),
  ('L-020', 'Estadistica para Administracion y Economia', 'David R. Anderson', 'Matematicas', 2016, '978-6075267463', 5, 'Estante E-1', 'Descriptiva, inferencia y regresion con ejercicios resueltos.');
