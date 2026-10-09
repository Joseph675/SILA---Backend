# SILA — estado del trabajo

Última sesión: **2026-10-09**. Este archivo es el punto de retomada.
Léelo primero, junto con `ENDPOINTS.md` y `~/sila_llanolat/README.md`.

---

## Las tres piezas y dónde están

| Pieza | Ruta | Estado |
|---|---|---|
| Base de datos | `~/sila_llanolat/` (scripts) | **Desplegada y verificada** |
| Backend | `~/sila-backend/` | Núcleo + catálogos funcionando |
| Dashboard | `~/Documentos/dashboard/dashboard-app/` | 1 de 15 pantallas |

Oracle XE 21c en Docker (contenedor `oracle-xe`), PDB **XEPDB1**.

### Cómo conectarse

```bash
# dueño del esquema (DDL, desplegar) — sin clave propia, entra por proxy
docker exec -it oracle-xe sqlplus "system[llanolat]/joseph123@localhost:1521/XEPDB1"

# backend
cd ~/sila-backend
export LLANOLAT_APP_PWD=$(awk '/^LLANOLAT_APP/{print $2}' ~/sila_llanolat/credenciales.txt)
./mvnw spring-boot:run

# dashboard
cd ~/Documentos/dashboard/dashboard-app && npm start   # :4200
```

Claves en `~/sila_llanolat/credenciales.txt` (chmod 600).

---

## 1. Base de datos — LISTA

20 tablas, 21 vistas, 14 packages, 19 triggers, 3 funciones, 58 índices.
Las tres consultas de verificación de la Parte K dan **cero filas** y las
pruebas de humo pasan completas, incluido el `ORA-00942` que confirma que la
aplicación no puede leer una tabla directamente.

### Tres bugs corregidos del documento original

El documento declaraba que su código nunca se ejecutó contra Oracle. Al
compilarlo aparecieron 3 defectos que un parser no detecta. Están en
`~/sila_llanolat/02_esquema_llanolat.sql`; el original sin parches está en
`00_original_del_word_sin_parches.sql` para hacer diff.

1. **ORA-01786** en `pkg_ventas.sp_registrar_venta` y 2. el mismo en
   `pkg_facturacion.sp_registrar_nota_credito`: `FOR UPDATE` no admite un
   `JSON_TABLE` dentro del subquery. Aislado probando variantes: `ORDER BY`
   con `FOR UPDATE` funciona, `TABLE(coleccion)` funciona, `JSON_TABLE` no.
   Corregido con `sys.odcinumberlist`, conservando el bloqueo ordenado en una
   sola consulta.
3. **PLS-00204** en `trg_aud_parametros`: `DECODE` solo existe en SQL.
   Reemplazado por comparación explícita que preserva la semántica NULL-segura.

### Datos cargados

- 10 proveedores (recuperados del respaldo) con documento marcador
  `PEND-0001`..`PEND-0010` — los datos originales no traían NIT
- 24 quincenas de 2026, 7 categorías de gasto, 5 parámetros sembrados
- Datos de prueba de esta sesión: 2 clientes, 3 productos con stock

---

## 2. Backend — núcleo y catálogos funcionando

47 archivos Java, 40 endpoints (22 GET, 10 POST, 2 PUT, 2 PATCH, 4 DELETE).
Spring Boot **4.1.1** (no `4.1.1.RELEASE`, ese artefacto no existe) + Java 25.
Boot 4 renombró: `spring-boot-starter-webmvc` y `ojdbc17`.

Implementado: `infra/` completo (`SilaTemplate`, `SilaException`,
`SilaExceptionHandler`, `Identidad`), la capa de lectura de las 19 vistas, y
la escritura de clientes, proveedores, productos, períodos y categorías.

`SilaTemplate` es la pieza crítica: una sola conexión por operación. El
contexto `CTX_SILA` vive en la conexión, así que un interceptor separado NO
funciona — usaría otra conexión del pool y el procedimiento correría sin
identidad.

---

## 3. Dashboard — diagnóstico

Angular 22 + Tailwind 4 + ApexCharts. **No es un mockup, pero tampoco una app
funcionando.** Tres capas de distinta calidad:

1. Un template de admin convertido a Angular: `Dashboard`, `Customers`,
   `Orders`, `Products`, `Users`, `Files` — datos de e-commerce genérico.
2. **Andamiaje propio y bien hecho**: `ApiService`, `Page<T>`, `ApiError` con
   los códigos ORA, patrón repositorio con swap mock/HTTP por
   `fileReplacements`, `MockBackendService` con latencia simulada, kit de UI
   de 13 componentes.
3. **Un módulo SILA real**: catálogos (1.819 líneas, 20 archivos).

Del sidebar: **1 pantalla construida** (Catálogos), **4 prestadas del
template** (Tablero, Historial de ventas→`/orders`, Cartera→`/customers`,
Inventario→`/products`), **10 sin pantalla** y deshabilitadas.

### Deuda a limpiar

- `features/proveedores/` (205 líneas) es **código muerto duplicado**: estilo
  template, con ruta `/proveedores` activa pero fuera del sidebar. El real
  está en `catalogos/proveedores`. Borrarlo.
- **Cero tests** pese a vitest configurado.
- `docs/CONTRATO_BACKEND.md` se referencia desde
  `http-catalogos.repository.ts` pero **nunca se escribió**. Es la causa raíz
  del desajuste de contratos.

---

## HECHO (2026-10-07): backend adaptado al contrato del dashboard

Los seis cambios están aplicados y verificados contra Oracle para Catálogos
(clientes, proveedores, productos): prefijo `/api/v1/catalogos/...`, `Page<T>`
con paginación real y orden por lista blanca, JSON snake_case con `activo`
booleano, entidad completa tras escribir, `PATCH /{id}/estado`, error
`{code,message,support_ref}` y CORS para :4200. Además `PUT /productos/{id}`
usa el nuevo `sp_actualizar_producto`.

**Fuente de verdad: `~/Documentos/dashboard/dashboard-app/docs/CONTRATO_BACKEND.md`.**

Estructura del código (por módulo, y dentro de cada uno por capa):

    infra/        transversal: SilaTemplate, Identidad, Page, Paginacion, ConsultaPaginada,
                  Texto, CorsConfig, manejo de errores
    catalogos/    controller/ (CatalogoController = clientes, proveedores, productos;
                              PeriodoFinanzaController = periodos y categorias de gasto)
                  service/    CatalogoService
                  repository/ Cliente|Proveedor|ProductoRepository (escritura, packages),
                              CatalogoConsultaRepository (lectura, vistas),
                              CatalogoRepository (periodos, finanzas y cambiar estado)
                  dto/
    consultas/    controller/ ConsultaController   repository/ OperacionConsultaRepository   dto/

Se eliminaron ClienteController/Service y CatalogoConsultaRepository antiguos y
las rutas `/api/clientes|proveedores|productos`. 7 pruebas unitarias de
`Paginacion` (`./mvnw test -Dtest=PaginacionTest`).

Limitaciones conocidas:
- `Idempotency-Key` se acepta (CORS) pero no se usa: un POST repetido crea duplicado
  salvo por las restricciones únicas.
- `PUT /clientes` con `limite_credito` ejecuta dos procedimientos (límite y luego
  datos); no son atómicos entre sí. El límite va primero para que un 403 no
  deje cambios a medias.
- Todas las rutas ya cuelgan de `/api/v1` (periodos, finanzas, consultas…), pero
  solo Catálogos sigue el contrato completo (Page<T>, entidad tras escribir,
  PATCH estado). Las demás devuelven listas simples y se adaptarán cuando se
  construya su pantalla.
- Los datos de prueba (TEST-*, 1 cliente, 1 proveedor, 2 productos) quedaron
  desactivados en la base: los triggers impiden borrarlos.

## HECHO (2026-10-08): módulo Períodos

Paquete nuevo `periodos/` (controller, service, repository, dto) con el contrato del dashboard:
`GET /periodos` (Page, resultado estimado incluido), `/anios`, `/actual`, `/{id}`,
`POST /anio/{anio}` y `PATCH /{id}/estado` (cerrar / reabrir con motivo). Todo ADMIN en escritura.
Contrato en `docs/CONTRATO_BACKEND.md` del dashboard, sección "Períodos". Verificado contra Oracle
(lectura, filtros, sort inválido, 403 de OPERATIVO, idempotencia, 20010, motivo obligatorio, 404).
Se eliminaron `POST /{id}/cerrar`, `POST /{id}/reabrir`, `GET /periodos/resultado` y `ReabrirPeriodoRequest`.
Quedó una reapertura/cierre de prueba de la quincena 1 en `auditoria_cambios` (inmutable).

## HECHO (2026-10-08): login, usuarios y segundo factor

**Base de datos** (`~/sila_llanolat/06_usuarios_login.sql`, ya aplicado): `usuarios_app` (hash BCrypt,
bloqueo, MFA), `sesiones_usuario` (renovación con familias), `pkg_autenticacion` (sin sesión: lo usa el
login), `pkg_usuarios` (solo ADMIN), `vw_usuarios`, 3 parámetros (`LOGIN_MAX_INTENTOS`=5,
`LOGIN_BLOQUEO_MINUTOS`=15, `SESION_MAX_HORAS`=24). Primer ADMIN creado: `kevin.mijares` (clave temporal y
claves del backend en `credenciales.txt`). No hay tabla de usuarios borrable ni se lee el hash fuera del package.

**Backend** (`seguridad/`): Spring Security sin estado, JWT HS256, login por pasos (cambiar clave → MFA),
TOTP RFC 6238 verificado con los vectores del RFC, secreto MFA cifrado AES-GCM, token de renovación en cookie
httpOnly/SameSite=Strict con rotación y detección de reutilización, bloqueo por intentos (base) y límite de
frecuencia (memoria), `/usuarios` para ADMIN con reglas anti-autobloqueo. Modo `jwt` POR DEFECTO, también en desarrollo
(`./arrancar.sh`; `./arrancar.sh desarrollo` solo por excepción); el modo desarrollo no arranca con perfil de producción. Además: backend solo en
127.0.0.1 (`SILA_BIND` para cambiarlo), `/actuator/health` sin detalles. 26 pruebas unitarias + 56
comprobaciones de integración contra Oracle (flujo completo, anti-replay, reuso de renovación, anti-CSRF,
roles verificados por la base, bloqueo, 429).

**Pendiente:** el dashboard (prompt en `docs/PROMPT_LOGIN.md` del dashboard); con el backend ya en modo `jwt`, el dashboard
actual (que manda cabeceras) recibe 401 hasta que tenga su pantalla de login. Siguientes huecos de seguridad (ver documento de seguridad): HTTPS/proxy, claves
fuera de `01_llanolat_bootstrap.sql` y de este archivo (rotar), Idempotency-Key, escaneo de dependencias,
pruebas por rol en cada módulo nuevo. Cuentas `prueba.admin` / `prueba.operativo` (y `prueba.temp`, inactiva)
son de desarrollo: desactivar antes de producción.

## HECHO (2026-10-09): endurecimiento local

- **Idempotency-Key** (`infra/idempotencia/`, `07_seguridad_extra.sql`): reintentos no duplican; obligatoria en
  `/ventas`, `/cartera`, `/facturacion`; solo guarda respuestas 2xx, 24 h, por usuario; ni `/auth` ni `/usuarios`.
- **Revocación inmediata** (`seguridad/VerificadorUsuario`): un token de acceso deja de servir en segundos si el
  usuario se desactiva o cambia de rol (caché de 30 s, invalidada al instante por los cambios hechos desde el API).
- **Escrituras ADMIN** también exigidas por la cadena de seguridad (periodos, PATCH de catálogos, crear productos);
  la base sigue verificándolas.
- **Perfil `prod`** (`application-prod.yml`): logs INFO, errores sin trazas, límites de tamaño y tiempos, actuator solo
  en 127.0.0.1:8081, cookie siempre Secure. Verificado: arranca, devtools NO entra al jar.
- **Dependencias:** `python3 pruebas/dependencias.py` (consulta OSV). Encontró Tomcat 11.0.24 (3 críticas) y Jackson 3.1.5
  (varias altas): se fijaron `tomcat.version=11.0.25` y `jackson-bom.version=3.1.7` en el `pom.xml`. Quitarlas cuando Spring
  Boot traiga esas versiones o mayores.
- **Pruebas de seguridad en el repo:** `python3 pruebas/seguridad.py` (60 comprobaciones contra Oracle: login por pasos, MFA,
  renovación, anti-CSRF, bloqueo, revocación, Idempotency-Key y una matriz de 96 combinaciones endpoint × rol; crea y
  desactiva sus propios usuarios t.*). **Al agregar un endpoint, agregarlo a `MATRIZ`.** 28 pruebas unitarias con `./mvnw test`.
- Pendiente (decidido): códigos de respaldo MFA, registro/pantalla de auditoría de accesos, gestión de sesiones;
  dashboard: cierre por inactividad, sincronizar pestañas, CSP, `npm audit fix`.

## HECHO (2026-10-09, 2.ª parte): códigos de respaldo, sesiones y auditoría de accesos

- **Códigos de respaldo MFA:** 10 códigos de un solo uso al activar el segundo factor (solo se guarda su SHA-256);
  `POST /auth/mfa/respaldo` para entrar sin teléfono, regenerar con la clave actual, borrado automático al restablecer el MFA.
- **Sesiones:** el token de acceso lleva `sid`; cerrar una sesión (logout, cerrar otras/todas, cambio de clave, ADMIN, desactivar)
  corta también su token de acceso en segundos. `GET/DELETE /auth/sesiones…`, `/usuarios/{id}/sesiones` y `/cerrar-sesiones`.
- **Bitácora de accesos** (`eventos_acceso`, solo inserción): cada login, fallo, bloqueo, logout y cambio, con IP y navegador;
  los intentos con usuarios inexistentes se limitan por IP. `GET /auditoria/accesos|cambios|errores` (solo ADMIN).
- **Manejador general de errores:** una excepción inesperada ya nunca devuelve trazas ni detalles (antes, en desarrollo, Spring
  mostraba el stack completo). 404/405 salen en el formato uniforme.
- **Pruebas:** `python3 pruebas/seguridad.py` = 99 comprobaciones; `python3 pruebas/crear_usuario_prueba.py ADMIN|OPERATIVO|--limpiar`
  para que quien desarrolla el dashboard tenga usuarios demo; 32 pruebas unitarias.
- SQL a aplicar en orden: 06, 07, 08 (todos reejecutables).

## HECHO (2026-10-09, 3.ª parte): Acopio y Producción/Inventario

Módulos `acopio/` y `produccion/` (lectura paginada por vistas nuevas, `09_vistas_acopio_produccion.sql`; escritura por
`pkg_acopio` y `pkg_produccion`, ya existentes). Recepciones de leche (calidad APROBADO/RECHAZADO por umbrales),
lotes de producción (suman stock), ajustes de inventario (tipos de resta: OPERATIVO; suma/corrección: ADMIN), inventario,
resumen y alertas de caducidad. Fechas por defecto en hora de Colombia (la base usa UTC). `Idempotency-Key` obligatoria en
las escrituras. Verificado con `pruebas/seguridad.py` (sección 9d: proveedor y producto desechables, costo 0): 121/121.
Prompt del dashboard: `docs/PROMPT_ACOPIO_PRODUCCION.md`. Siguiente natural: Ventas.

## HECHO (2026-10-09, 4.ª parte): Ventas, Precios pactados, Cartera y Parámetros

- **Ventas** (`/api/v1/ventas`): venta con líneas (el servidor calcula precio, IVA y total), CONTADO con **varios medios de pago**
  que suman el total o CREDITO dentro del cupo; anulación (solo ADMIN; no si hay abonos o factura enviada); punto de venta
  `GET /ventas/precios?id_cliente=`. **Precios pactados por cliente** (`/api/v1/precios-cliente`, solo ADMIN).
- **Cartera** (`/api/v1/cartera`): clientes con deuda y estado de crédito, resumen, ventas pendientes, abonos (reparto FIFO,
  un recibo por pago) y castigos (ADMIN).
- **Parámetros** (`/api/v1/parametros`): lista y edición (ADMIN) con rango por parámetro y auditoría.
- Base de datos: `10_ventas_precios_pagos.sql` y `11_parametros_editables.sql`. La venta y el abono usan el día de COLOMBIA
  (antes SYSDATE/UTC). Cliente CONSUMIDOR FINAL (CC 222222222222) creado.
- Verificado con `pruebas/seguridad.py` (secciones 9d y 9e; valores de pocos pesos, clientes/productos desechables; la venta de
  contado de prueba se anula; la de crédito se paga con un abono). Prompt del dashboard: `docs/PROMPT_VENTAS_CARTERA.md`.
- Rutas antiguas retiradas: `GET /ventas` sin paginar, `/ventas/{id}/detalle`, `GET /cartera`, `GET /cartera/abonos` sin Page.
- Pendiente de decisión para Facturación: resolución DIAN real y si se factura con proveedor tecnológico (usa SYSDATE: revisar la hora).

## HECHO (2026-10-09, 5.ª parte): editar mi perfil y MFA en acciones sensibles

- `PUT /auth/yo` (nombre y correo, `12_perfil_propio.sql` → `pkg_perfil`, ya aplicado). Rol, usuario y estado no se tocan.
- Con sesión abierta, **cambiar clave**, **regenerar códigos de respaldo** y **editar perfil** exigen además el código MFA
  (6 dígitos o código de respaldo). El cambio de clave obligatorio del primer ingreso no lo pide (aún no hay MFA).
- Pruebas: `pruebas/seguridad.py` 168 comprobaciones; los códigos de respaldo se usan para las acciones sensibles porque el TOTP
  es de un solo uso por ventana. Prompt del dashboard: `docs/PROMPT_CUENTA_MFA.md`.

## HECHO (2026-10-09, 6.ª parte): precio de la leche, pagos a lecheros y cuentas de la quincena

- `13_cuentas_proveedores.sql` (aplicado): `precios_proveedor`, `pagos_proveedor`, `pkg_pagos_proveedor`, `fn_precio_litro`,
  columnas `precio_litro`/`valor_total` en `recepcion_leche`, vistas `vw_saldo_proveedores`, `vw_pagos_proveedor`,
  `vw_precios_proveedor`, `vw_cuentas_quincena`. `pkg_acopio` fija precio y valor al recibir (rechazada = 0).
- **Migración**: las 18 recepciones anteriores se valoraron con `PRECIO_LECHE_DEFAULT` = **2000 $/L (valor inventado: confirmar y
  corregir en Parámetros, o fijar el precio real por lechero)**. OJO: cambiar el parámetro NO revalora lo ya recibido.
- Pago de la leche **por quincena**: cada pago liquida una quincena y no supera su saldo. Precios y pagos: solo ADMIN. Pagos no se
  borran, se anulan con motivo.
- `GET /periodos` trae las cuentas de la quincena: dos miradas rotuladas, **resultado (devengado)** y **caja**. La leche comprada
  se muestra aparte; su costo entra al resultado vía `costo_produccion` (el costo/litro del lote se digita a mano: sería mejor
  proponerlo con el precio promedio de la quincena — pendiente).
- Pruebas: `pruebas/seguridad.py` = 187 comprobaciones. Prompt del dashboard: `docs/PROMPT_CUENTAS_PROVEEDORES.md`.

### Siguiente: el prompt para el agente del dashboard

Ya está redactado en la conversación. Pide implementar los 13 métodos de
`HttpCatalogosRepository`, agregar las cabeceras `X-Usuario`/`X-Rol` al
interceptor (solo con `mockAuth`), y poner `useMocks: false`.

**Correcciones al prompt**: decía "corre `npx vitest run` y arregla lo que
rompas" — no hay tests; cambiar por "escribe al menos un test por método
implementado". Agregar: borrar `features/proveedores/`; leer `support_ref`
(no `supportRef`) en el interceptor de errores; el stock llega como `stock`.

---

## Decisiones que esperan al usuario

1. ~~`sp_actualizar_producto` no existe.~~ **RESUELTO 2026-10-07**: agregado
   en `pkg_catalogos` (script `~/sila_llanolat/05_sp_actualizar_producto.sql`,
   ya integrado en `02_esquema_llanolat.sql`). Rol OPERATIVO. Edita nombre y
   unidad; la unidad solo cambia si el producto no tiene movimientos
   (ventas, ajustes, producción, notas crédito) ni stock → si no, ORA-20006.
   El trigger `trg_aud_productos` ahora también audita NOMBRE y UNIDAD_MEDIDA.
   Falta exponerlo en el backend: `PUT /api/v1/catalogos/productos/{id}`.
2. **IVA por producto**: confirmar con contabilidad uno por uno; varios
   lácteos están excluidos o exentos. Los 3 productos de prueba tienen
   valores plausibles, no verificados.
3. **Resolución DIAN real**: sin ella `sp_emitir_factura` rechaza todo.
   Hace falta una para FACTURA y otra para NOTA_CREDITO.
4. **Inventario por producto, no por lote**: sin FEFO, y las alertas de
   caducidad no reflejan existencias reales del lote. Para lácteos con 21
   días de vida útil es el límite funcional más serio del diseño.
5. Confirmar los 5 parámetros sembrados: 10 °C, 18 °Dornic, 21 días de vida
   útil, 30 días de plazo, 200 líneas por documento.

---

## Riesgos abiertos

- **`IdentidadDesdeCabecera` es un hueco de seguridad.** Cualquiera manda
  `X-Rol: ADMIN` y la base le cree. Solo se activa con
  `sila.seguridad.modo=desarrollo` y avisa por log, pero hay que
  reemplazarlo por JWT antes de producción.
- **Fuga de identidad entre préstamos del pool**: `CTX_SILA` vive en la
  conexión. `SilaTemplate` lo limpia en un `finally`, pero si algún día se
  llama un procedimiento fuera de él, hereda la identidad anterior. El
  fallback `NVL(..., SESSION_USER)` en las tablas lo oculta en vez de fallar
  — considerar cambiarlo por error duro.
- **Sin retención ni purga** de `log_errores` ni `auditoria_cambios`, y los
  triggers append-only obligan a deshabilitarlos para purgar. XE tiene tope
  de 12 GB de datos de usuario.
- La contraseña de SYS (`joseph123`) está en texto plano en las variables de
  entorno del contenedor, visible con `docker inspect`.

---

## Falta construir

**Backend**: escritura de ventas, cartera, facturación, acopio, producción,
gastos y documentos (7 módulos). Ventas es el interesante: recibe el JSON de
líneas y hace el bloqueo ordenado que se parcheó.

**Dashboard**: 14 pantallas. El módulo de catálogos sirve de plantilla.

**Ambos**: Spring Security + JWT.


---

## PUNTO DE RETOMADA (cierre de sesión 2026-10-09)

**Pendiente inmediato**
1. Reiniciar el backend (`./arrancar.sh`; con ngrok: `SILA_NGROK=https://<dominio>.ngrok-free.dev ./arrancar.sh`).
2. Confirmar `PRECIO_LECHE_DEFAULT` (hoy 2000 $/L, inventado). Si el real es otro, revalorar las 18 recepciones migradas con un script.
3. Pasar al agente del dashboard: `docs/PROMPT_CUENTA_MFA.md` y `docs/PROMPT_CUENTAS_PROVEEDORES.md`.

**Siguiente por construir (backend)**: Gastos y Documentos; Facturación (espera resolución DIAN); Tablero.

**Ideas decididas/propuestas sin hacer**
- Límite de sesiones simultáneas por usuario (propuesto 3–5, cierra la más antigua) y pantalla «usuarios conectados» para ADMIN. Hoy no hay tope.
- Proponer el `costo_por_litro` del lote con el precio promedio de la quincena.
- Los límites de frecuencia viven en memoria (se pierden al reiniciar; no sirven con varias instancias).
- Descartado por el usuario: corregir el precio de una recepción individual.

**Documento de presentación**: `TEMAS_PRESENTACION.txt` (agregar la sección de límites y la de cuentas de proveedores).
Pruebas: `python3 pruebas/seguridad.py` (187 comprobaciones; levantar el backend aparte) y `./mvnw test`.
