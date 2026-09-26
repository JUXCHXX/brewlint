<div align="center">
  <img src="brewlintlogo.png" alt="Brewlint" width="500" />

  # Brewlint

  **Análisis estático para anti-patrones de Spring Boot.** Reglas curadas con un parser real de Java, no con expresiones regulares.

  [![CI](https://github.com/JUXCHXX/brewlint/actions/workflows/ci.yml/badge.svg)](https://github.com/JUXCHXX/brewlint/actions/workflows/ci.yml)
  [![npm version](https://img.shields.io/npm/v/brewlint.svg)](https://www.npmjs.com/package/brewlint)
  [![VS Code Marketplace](https://img.shields.io/visual-studio-marketplace/v/juxchxx.brewlint.svg?label=VS%20Code)](https://marketplace.visualstudio.com/items?itemName=juxchxx.brewlint)
  [![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

</div>

---

## ¿Qué problema resuelve?

Los bugs que Brewlint busca comparten una misma forma: **el código se ve bien, compila, se despliega... y simplemente no hace nada.**

Un `@Transactional` sobre un método `private` es aceptado por el compilador y por Spring, y jamás abre una transacción. Un `InputStream` sin cerrar funciona perfecto hasta que, bajo carga, el proceso se queda sin descriptores de archivo — en una parte completamente distinta del sistema. Ninguno de los dos lanza una excepción, ninguno queda en los logs, y ninguno lo detecta una revisión de código que lee la intención en vez de mirar el detalle técnico línea por línea.

Brewlint encuentra justo esos errores silenciosos, antes de que lleguen a producción.

> **Estado: los 8 hitos del roadmap están completos.** 9 reglas, un binario autocontenido (sin necesidad de tener Java instalado), un análisis opcional con IA (dos proveedores), reportes en terminal, JSON y PDF, extensión de VS Code, GitHub Action, y más de 400 tests. Ver [Roadmap](#roadmap).

---

## Instalación en 10 segundos

```bash
npm install -g brewlint        # no necesitas tener Java instalado
brewlint scan --path src
```

Eso es todo. El paquete de npm trae su propio runtime empaquetado (~48 MB por plataforma), así que Brewlint corre incluso en una máquina que nunca ha tenido un JDK.

## Qué reporta

```
brewlint 0.1.0

  src/main/java/com/example/broken/OrderService.java

    ERROR    17:5      AOP001
      @Transactional en un método private: el proxy de Spring no puede interceptarlo,
      así que la transacción nunca se abre ni se confirma.

      -> Haz el método público y llámalo desde otro bean.

  ------------------------------------------------------------------------------
  29 hallazgos en 7 archivos · 17 errores, 10 advertencias, 2 informativos · 197ms
```

### Reglas

| Id | Categoría | Severidad | Qué detecta |
|---|---|---|---|
| `AOP001` | proxy de Spring | error | `@Transactional`, `@Async`, `@Cacheable`, `@Scheduled` (y otras) sobre un método `private`, `final` o `static`, que Spring no puede interceptar |
| `TX002` | transacciones | error | Un método `@Transactional` llamado desde dentro de su propia clase, saltándose el proxy |
| `TX003` | transacciones | advertencia | `@Transactional` sin `rollbackFor`, cuando puede escapar una excepción checked y Spring confirma en vez de revertir |
| `RES001` | recursos | error | Un `InputStream`, `Connection`, `Statement` u otro recurso que nadie cierra |
| `RES002` | recursos | advertencia | Un stream de `Files.lines/list/walk/find` que nunca se cierra |
| `BEAN001` | beans | error | Una entidad JPA/Mongo también anotada como `@Component` |
| `BEAN002` | beans | advertencia | Un bean `prototype` inyectado en un singleton, compartido por todos los que lo usan |
| `BEAN003` | beans | info | Inyección por campo (`@Autowired`), en vez de por constructor |
| `PERF001` | rendimiento | advertencia | Una consulta dentro de un bucle — el clásico problema N+1 |

## Uso

```
brewlint scan [opciones]

  -p, --path <dir>        Directorio o archivo .java. Por defecto: el directorio actual.
  -c, --config <file>     Ruta a brewlint.yml.
      --format <formato>  terminal, json o pdf. Por defecto: terminal.
  -o, --output <file>     Escribe el reporte a un archivo en vez de la terminal.
      --fail-on <sev>     Sale con código 1 en esta severidad o mayor: ERROR, WARNING, INFO, NONE.
      --ai <proveedor>     anthropic u ollama. Desactivado por defecto.
```

Los códigos de salida son un contrato pensado para CI:

| Código | Significado |
|---|---|
| `0` | Limpio, o hallazgos por debajo del umbral |
| `1` | Hallazgos en o sobre `--fail-on` |
| `2` | Brewlint no pudo correr (ruta inválida, config mal formada) |

## La extensión de VS Code

Hallazgos mientras escribes, directo en el panel de Problemas del editor.

```bash
code --install-extension juxchxx.brewlint
```

No necesita Java — trae su propio runtime, igual que el CLI.

## El reporte en PDF

El que adjuntas a un pull request o le mandas a tu equipo.

```bash
brewlint scan --path src --format pdf --output reporte.pdf
```

El color nunca es la única forma de transmitir un significado: cada severidad se escribe también en palabras, para que el reporte sea igual de claro impreso en blanco y negro o para alguien con daltonismo.

## El análisis opcional con IA

Las reglas curadas detectan lo que el árbol de sintaxis puede probar con certeza. No pueden detectar que un límite transaccional está en el lugar equivocado *para este proyecto en particular*. Un modelo de lenguaje sí puede leer el código y decirlo — eso es exactamente lo que hace esta capa, y es puramente aditiva.

```bash
brewlint scan --path src                                  # solo reglas, siempre completo
ANTHROPIC_API_KEY=... brewlint scan --path src --ai anthropic
ollama serve && brewlint scan --path src --ai ollama      # nada sale de tu máquina
```

Tres garantías, por diseño:

- **Nunca es obligatorio.** El reporte queda completo aunque no haya ninguna IA configurada.
- **Nunca supera a las reglas.** Todo hallazgo de IA queda limitado a `WARNING`, y se marca como `AI001` — una regla puede justificar un `ERROR` porque decidió algo con certeza; un modelo no.
- **Nada sale en silencio.** Cada línea de código pasa por un redactor de secretos antes de enviarse a cualquier API externa.

## La GitHub Action

Hallazgos directamente en un pull request: anotaciones en el diff, y un solo comentario que se actualiza en el mismo lugar en cada push (no uno nuevo que entierre la conversación).

```yaml
- uses: JUXCHXX/brewlint@v0.1.0
  with:
    path: .
    fail-on: ERROR
```

## Configuración

Un archivo `brewlint.yml` en la raíz del proyecto — completamente opcional, con buenos valores por defecto:

```yaml
rules:
  AOP001: true
  RES001:
    enabled: true
    severity: WARNING

exclude:
  - "**/generated/**"
```

## Arquitectura, en breve

```
brewlint/
├── brewlint-core/     Parser, motor de reglas, modelo de hallazgos. Sin IA, sin terminal, sin PDF.
├── brewlint-ai/       Capa opcional de IA: Anthropic, Ollama, redacción de secretos.
├── brewlint-report/   Renderizadores: terminal, JSON, PDF.
├── brewlint-cli/      El binario ejecutable.
├── editors/vscode/    La extensión de VS Code.
└── fixtures/          Proyecto Spring roto a propósito, usado para probar las reglas.
```

`brewlint-core` nunca depende de `brewlint-ai` — esa dirección es lo que garantiza que el reporte quede completo sin importar si hay IA configurada o no.

> Las decisiones técnicas más profundas (por qué cada regla se diseñó así, los bugs que aparecieron en el camino, y el detalle de empaquetado multiplataforma) están documentadas a fondo en el código y, próximamente, en una página dedicada. Este README se queda en lo esencial a propósito.

## Roadmap

| Hito | Alcance | Estado |
|---|---|---|
| 1 | Andamiaje, motor de reglas, CLI, reporte en terminal | ✅ |
| 2 | Reglas completas, salida en JSON | ✅ |
| 3 | Runtime autocontenido, instalación vía npm sin Java | ✅ |
| 4 | Capa de IA (Anthropic + Ollama) | ✅ |
| 5 | Reporte en PDF | ✅ |
| 6 | Extensión de VS Code | ✅ |
| 7 | GitHub Action | ✅ |
| 8 | Detección de N+1 | ✅ |

## Contribuir

`./mvnw test` debe pasar. Una regla nueva necesita:

1. Una clase que implemente `Rule`.
2. Una línea en `META-INF/services/io.github.brewlint.core.rule.Rule`.
3. Tests con el caso roto **y** el caso correcto que no debe disparar.

Una regla que no puede probar que se queda callada en código correcto no está lista.

## Licencia

MIT. Ver [LICENSE](LICENSE).
