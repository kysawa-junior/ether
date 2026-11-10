# Plano de implementação: Ether como motor de mapa 2D/3D do Urban

**Data:** 2026-11-10 · **Spec:** `docs/superpowers/specs/2026-11-10-ether-map-engine-design.md` (aprovada)
**Ordem das etapas respeita dependências; cada etapa termina com build/teste verde medido e commit.**
**Repositórios:** ether = git (push em `origin/main`); **Urban NÃO é repo git** (nota: etapa 7
propõe `git init`/remote para o Urban — decisão do usuário).

## Fontes de verdade do port

- `urban/engine-standalone/engine/render/*.kt` (9 arquivos, ~1.685 linhas) → transposição
  para HAL do ether em `modules/ether-render-lwjgl`.
- `urban/engine-standalone/engine/{osm,geo,geom,mesh,scene,camera,streaming,interaction,core,runtime,rendering}/` →
  `modules/ether-map` (com `ether-math` no lugar de Mat4/V3 locais).
- `urban/engine-standalone/engine/map-data/` → `modules/ether-map-data` (formato
  `urban-map-package` v1; preciso de `MAP_DATA_FORMAT.md` como referência).
- `urban/engine-standalone/engine/graphics-api` (GraphicsApi.kt inline, 224 linhas) →
  **descartado** (a HAL do ether já existe e é mais rica).
- Nativos: `urban/engine-standalone/engine/resources/natives/linux/liblwjgl.so` →
  copiados para `modules/ether-render-lwjgl/resources/natives/linux/` com `NativeBootstrap`.

## Etapas

### Etapa 0 — Consumo mavenLocal (prova de interface entre projetos)

1. `./kotlin publish` no ether para `mavenLocal` (verificar alvo/flags do wrapper).
2. Em Urban, adicionar repositório `mavenLocal` + dependência de prova (um artefato
   `ether-render-api` em `server` ou `app/shared`) e confirmação de resolução de build.
3. **Gate:** se o Amper recusar cross-project mavenLocal, decisão: fallback documentado
   na spec (vendorizar) com checkpoint pelo usuário.
4. Reverter a dependência de prova (a real chega nas etapas 3–5).

### Etapa 1 — `ether-render-lwjgl` (backend Vulkan JVM)

1. Criar `modules/ether-render-lwjgl` (`product: jvm/lib`, `explicitApi: strict`,
   dependência `$libs.lwjgl` + `$libs.lwjgl.vulkan` — catálogo ether).
2. Transpor `VulkanDevice/VulkanResources/VulkanBuffers/VulkanPipeline/VulkanRenderer/`
   `VulkanGraphicsDevice/VulkanGraphicsMappings/VulkanGraphicsResources/VulkanTargets`
   para a forma do ether: `LwjglVulkanBackend : RenderBackend` +
   `LwjglVulkanBackendDevice : RenderBackendDevice` (HAL completa: writeBuffer,
   readTextureBytes, createCommandBuffer/submit de `BackendCommand`,
   `isSubmissionComplete`, `waitIdle`, `destroy*`, `close`; `NativeBootstrap` extração única
   de natives).
3. Smoke tests portados (JVM, device real RADV): triângulo 64×64 com os mesmos asserts
   ( centro vermelho/canto preto/alpha, 512 px + extensões de linha), portable
   `GraphicsDevice` end-to-end, readback token inválido.
4. **Gate:** suite verde, 0 warnings, contagem medida registrada no README.

### Etapa 2 — `ether-map-data`

1. Módulo `product: kmp/lib` multiplataforma (`jvm, linuxX64, wasmJs`…); port de
   manifesto/tiles/features/checksums/validação (packaging `urban-map-package` v1).
2. Testes portados de `map-data` (IDs, coordenadas, checksums) + novos de leitura.
3. **Gate:** verde em JVM + linuxX64 com contagem medida.

### Etapa 3 — `ether-map` (runtime de mapa)

1. Port módulo-a-módulo: `osm` (OsmXmlReader, FeatureMapper), `geospatial`
   (ENU/tiles), `geom` (triangulação com buracos — algoritmo exato), `mesh`
   (vias/edifícios/caixas), `scene` (Scene/SceneGraph/SceneBuilder, Palette/Fleet),
   `camera` (OrbitCamera), `streaming` (LRU), `core` (lifecycle transacional),
   `runtime` (MapRuntime) + `rendering` (RenderQueue sobre CommandEncoder).
2. `MapModePolicy` (T_CLOSE=220 m / T_FAR=420 m, histerese, substituível) e
   `Overhead2D` ortográfico top-down vs `Street3D` perspectiva.
3. Substituir Mat4/V3 locais por `ether-math` (Mat4/Quat/Aabb); deletar duplicações.
4. Testes: os 20 do engine portados (parse real 402k nós, triangulação, faixa,
   orientação via raycast, LRU, pacote, lifecycle) + novos da política 2D/3D
   (troca abaixo de T_CLOSE, volta acima de T_FAR, sem oscilação na fronteira).
5. **Gate:** suite verde (alvo ≥ 30 testes), acesso a hardware real para render
   headless com device LWJGL.

### Etapa 4 — Urban: remoção do engine deprecated

1. `rm -rf engine-standalone/`; `project.yaml` do root sem módulos engine.
2. Docs: README (módulos e execução), STATUS, ARCHITECTURE, PLATFORM_COMPATIBILITY,
   MAP_DATA_FORMAT (aponta para ether-map-data), ADR-005 → superseded +
   `ADR-007-ether-adoption.md`.
3. `libs.versions.toml`: remover lwjgl amarradas ao engine (o backend vive no ether).
4. **Gate:** build/testes do Urban verdes; `grep -r engine-standalone` só história.

### Etapa 5 — Urban: consumir ether

1. `app/shared` deps: ether-map (+ transitivos), ether-render-lwjgl, ether-render-null;
   registro do backend no runtime do app (`com.instrum.urban.Main`).
2. Regras de versão fixadas; re-build do app JVM.
3. **Gate:** resolução determinística e build verde.

### Etapa 6 — Central de Operações 3D

1. `CentralMapScreen` em `app/shared`: estado `MapMode { Overhead2D, Street3D }`
   via `MapModePolicy`; carga inicial = 2D; troca por distância de câmera; histerese;
   gestos (drag= zoom/pan, clique=raycast/seleção) da antiga MapScreen mínimos.
2. Superfície: 1ª entrega offscreen+apresentação por frame no Compose (visible,
   lenta); swapchain xlib como melhorias; sem claim público antes de medir.
3. Fallback sem GPU: Compose Canvas vetorial (anéis/contornos da cena) + indicador
   "sem GPU"; Sem alegar 3D.
4. Teste instrumentado (JVM): estados 2D→3D→2D nos limiares com o policy declarado.
5. **Gate:** rota funcional no app desktop real (RADV), teste de política verde.

### Etapa 7 — Docs/ADRs + validação final

1. ether: entradas de ADR para verticais de mapa + backend LWJGL, RENDERING
   (matriz backend), README, CHANGELOG, contagens medidas.
2. Urban: ADR-005 superseded, ADR-007, README/STATUS/ARCHITECTURE atualizados
   com "engine = ether (módulos X.Y.Z)".
3. Suítes dos dois repos verdes com contagem medida; commit/push ether; commit/push
   urban (**sugestão ao usuário**: `git init` no Urban + remote).
4. **Gate final:** ADR-0017 respeitado — nada de não-validado como "supported".

## Pontos de decisão embutidos no plano

- `mavenLocal` vs vendorizar — decidido na Etapa 0 com dados reais do Amper.
- Swapchain xlib vs offscreen-first — decisão por medição na Etapa 6 (não por otimismo).
- Contagens de teste — reportadas sempre por execução (nunca por estimativa).
