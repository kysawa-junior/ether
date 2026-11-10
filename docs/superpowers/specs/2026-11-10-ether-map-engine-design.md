# Design: Ether como motor de mapa 2D/3D do Urban

**Data:** 2026-11-10
**Status:** Aprovado (desenho); implementação em planejamento
**Supersede:** `documentation/adr/ADR-005-engine-separation.md` (Urban) — a separação
permanece válida como princípio, mas o motor separado (`engine-standalone/`) será
removido e substituído pelos módulos do ether.

## 1. Objetivo

O projeto **Urban** deixa de ter um motor gráfico próprio. Toda a responsabilidade de
renderizar mapa 2D e 3D passa a pertencer ao **ether**. O módulo de engine 3D do Urban
(`engine-standalone/`), hoje deprecated, é removido do repositório e substituído por
módulos do ether consumidos como artefatos.

A Central de Operações 3D volta a funcionar no app desktop, com a política de troca
definida pelo usuário:

> O 3D carrega no início da aplicação; enquanto a câmera está afastada, é exibido o
> **2D**; e somente quando a câmera está **extremamente próxima** é que alterna para o
> **3D**.

## 2. Escopo

### Nesta migração (in)

- Novos módulos no ether (fonte única do motor):
  - `ether-render-lwjgl` — backend Vulkan para JVM via LWJGL 3.4.3 (transposição do
    driver Vulkan já provado do `engine-standalone/engine/render/*` para a HAL do
    ether: `RenderBackendDevice` + `BackendCommand`). Inclui o `NativeBootstrap`
    (extração de `liblwjgl.so` dos recursos do módulo em tempo de execução).
  - `ether-map-data` — formato do pacote cartográfico `urban-map-package` v1
    (manifesto, tiles, features, checksums, validação de IDs/coordenadas), portado
    de `engine-standalone/engine/map-data` (portable; explicit API).
  - `ether-map` — leitor OSM (`osm/`), conversão ENU/tiles (`geospatial/`),
    triangulação com buracos (`geom/`), mesh de vias/edifícios/caixas (`mesh/`),
    `Scene.fromPackage`, câmera de órbita, raycast, cache LRU e `MapRuntime` —
    portados e reescritos sobre `ether-math` (Mat4/Quat/Aabb existentes; a matemática
    duplicada do engine é descartada).
- Remoção completa de `engine-standalone/` do repositório do Urban (código, testes,
  `project.yaml`, doc ADR-005 atualizado com ponteiro para o ether).
- Reintegração da Central 3D em `app/shared` (rota "Central de Operações"), com a
  política 2D⇄3D por distância de câmera.
- Consumo formal: ether publica (`./kotlin publish`, `mavenLocal`) e o Urban declara
  as dependências nas versões fixadas do ether.
- Testes: suítes do ether com contagens medidas no pipeline (JVM/nativo), leitores de
  pixel em hardware real (RADV) herdados do smoke test do Vulkan; testes do
  engine-standalone portados com cobertura equivalente (alvo: os 20 testes atuais,
  podendo se dividir entre módulos).

### Fora do escopo (out, registrado)

- Backend Web (WebGL2) e Android/iOS do ether — permanecem "not started" (ADR-0017).
- `tools/map-compiler` e `tools/blender-pipeline` (Python) permanecem no repo do Urban;
  o ether consome **somente o pacote** que eles produzem (contrato de arquivo, não de
  build).
- O backend `engine/graphics/metal` não é transplantado; Metal no ether continua uma
  etapa futura (`not started`).
- Rota OSRM, painel de propostas, splash multi-janela, busca Nominatim e loader GLTF
  (décisões já fora do escopo no projeto Urban).

## 3. Arquitetura

```text
ETHER (motor)                                  URBAN (produto)
────────────────────────                       ────────────────────────
ether-math           (existe)                  app/desktopApp   (Compose)
ether-render-api     (existe)                  app/shared
ether-render-core    (existe)                   └── CentralMapScreen
ether-render-null    (existe)                       (2D⇄3D, câmera, seleção)
ether-render-vulkan  (existe; nativo               server         (Ktor)
│   linuxX64; provado em hardware)             tools/map-compiler (Python)
ether-render-lwjgl   (NOVO; JVM+LWJGL)
ether-map-data       (NOVO; portável)
ether-map            (NOVO; consome math+map-data+render-core)
```

Dependências (todas declaradas em `module.yaml`):

- `ether-map` → `ether-math`, `ether-map-data`, `ether-render-api`, `ether-render-core`
- `ether-render-lwjgl` → `ether-render-api` (+ LWJGL via catálogo do ether)
- `app/shared` (Urban) → artefatos ether: `ether-map` (transitivo: map-data + math +
  render-api), `ether-render-lwjgl` (registro do backend), `ether-render-null`
  (fallback testável na JVM sem Vulkan).

### Fluxo de dados

1. **Compilação do pacote** (existe, permanece): OSM regional → `tools/map-compiler`
   (Python) → pacote `urban-map-package` v1 (manifesto, tiles, features, checksums).
2. **Carregamento**: `ether-map` abre o pacote (validação de checksum/IDs) →
   `Scene.fromPackage` constrói o grafo de cena (vias, edifícios, caixas de frota,
   fundo) com `MeshInstance` compartilhando buffers por material.
3. **Renderização**: `MapRuntime` compila o estado de frame (matriz da câmera,
   viewport, culling de frustum) e envia comandos ao `GraphicsDevice` do ether
   (`CommandEncoder` → `submit` → `waitIdle` para o caminho offscreen/testes).
4. **Superfície no Compose**: AWT `Canvas` nativo como surface Vulkan (xlib) e/ou
   trilha de fallback: render offscreen + re-apresentação por frame (lento, visível,
   temporário).

### Política 2D⇄3D (da Central)

- Estado único por sessão: `MapMode { Overhead2D, Street3D }`.
- **Carga inicial**: `Overhead2D` — a mesma cena, com câmera **ortográfica** top-down
  alinhada ao norte (reuso do teste que já verifica orientação).
- **Troca para 3D**: somente quando `cameraDistance < T_CLOSE` (default `220 m`,
  nível de rua); a transição é única (um modo por frame de render).
- **Volta para 2D**: somente quando `cameraDistance > T_FAR` (default `420 m`);
  histerese entre T_CLOSE e T_FAR impede alternância na fronteira.
- Os limiares podem ser sobrescritos pelo aplicativo (`MapModePolicy`).

### Proibição de domínio no core do ether

Os módulos `ether-map*` são **verticais opcionais** (assinatura própria, como
`bevy_render` vs `winit`): o `ether-render-api/core` não recebe tipos de cena, OSM
ou município. Um backend do ether jamais conhece tipos de domínio. A dependência é
unidirecional e explícita: `ether-map` fala **através de** `ether-render-api`.

## 4. Contratos principais (rascunho de API)

```kotlin
// ether-map (rascunho público — versão final na etapa writing-plans)
public class MapRuntime(
    packageSource: MapPackageSource,     // arquivo/fluxo com validação embarcada
    device: GraphicsDevice,              // facade do ether-render-core
    modePolicy: MapModePolicy = MapModePolicy.Defaults,
) {
    public fun open()
    public fun renderFrame(): FrameResult  // caminho único headless e desktop
    public fun onCamera(distance: Float)   // política 2D⇄3D
    public fun pick(x: Int, y: Int): PickResult?
    public fun close()
}

// ether-render-lwjgl (mesma forma do VulkanBackend nativo existente)
public class LwjglVulkanBackend(public val preferredDeviceName: String? = null) : RenderBackend
```

`LwjglVulkanBackend` implementa a mesma HAL: `isAvailable()`, `capabilities()`,
`createDevice(descriptor)`, e o dispositivo retornado implementa `writeBuffer`,
`readTextureBytes`, `createCommandBuffer`/`submit` de `BackendCommand`,
`isSubmissionComplete`, `waitIdle`, `destroy*`, `close()`
(única diferença JVM: `NativeBootstrap` extrai os natives empacotados uma vez por
processo).

## 5. Erros e honestidade (ADR-0017)

- Ausência de driver/acelerador no host: `isAvailable() = false` → a Central exibe
  **mapa 2D estático substituto** — desenhado com Compose Canvas a partir dos anéis e
  contornos da cena (paths vetoriais, sem GPU) — com indicador "sem GPU"; sem alegar
  3D.
- Device loss: `MapRuntime.close()` e tela 2D com indicador "reconectando";
  reabertura é tentada de forma explícita pelo aplicativo.
- Pacote corrompido/OSM inválido: `MapPackageException` com a causa (arquivo/linha);
  falha real, sem skips silenciosos.
- Nenhuma plataforma é declarada sem validação (a matriz do ether permanece medida).

## 6. Testes e critérios de aceitação

1. **ether-render-lwjgl**: suíte JVM headless no RADV real — o mesmo triângulo do
   smoke test com leitura de pixel (mesmos asserts: 512 px, extensões de linha).
2. **ether-map**: parse OSM real (402k nós, 82k ways → 4.6k vias, 75k edifícios),
   triangulação com buracos, largura de faixa, cache LRU, `Scene.fromPackage`
   (checksum inválido rejeita com causa), câmera/raycast e ENU/tiles —
   contagem alvo: ≥ 30 testes (os 20 do engine + novos dos módulos).
3. **Central 3D (app)**: teste instrumentado no desktopApp JVM que monta o
   `MapRuntime` + estados (2D inicial, troca para 3D abaixo de T_CLOSE, volta 2D
   acima de T_FAR) com a política de limiares declarada e conferida.
4. **Builds verdes**: todas as suítes dos dois repositórios (ether = suítes atuais +
   novas; urban = 15 server + app + mapa).
5. Sem `engine-standalone/` no repo: `grep -r engine-standalone` retorna apenas
   referências de história/compreensão que apontam para o ether.

## 7. Riscos

| Risco | Mitigação |
|---|---|
| Superfície Vulkan dentro do AWT/Compose | Entregar primeiro com render offscreen + apresentação por frame (mais lento, mas visível); swapchain xlib como segunda etapa; nenhum claim público sem medição |
| Backend LWJGL divergir dos backends nativos | Transposição linha-a-linha do driver provado; ambos usam a MESMA HAL/`BackendCommand` do ether |
| mavenLocal entre dois projetos Amper | Publicar com versionamento explicito; fallback declarado: vendorizar os módulos do ether no tree do Urban (replicação de fonte — último recurso) |
| Portagem de osm/geom/câmera | Transposição módulo-a-módulo com os testes originais como guia; testes substitutos quando a API mudar |

## 8. Encadeamento de ADRs (pós-implementação)

- **Urban `ADR-005`** recebe `Status: Superseded pela migração ether` + novo
  `ADR-007-ether-adoption.md` registra a reintegração com limiares 2D/3D.
- **ether** registra os verticais `ether-map*` + o backend LWJGL (mantendo
  `explicitApi`, testes medidos, sem alegar suportes não validados).
