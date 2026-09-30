# Modular Bridge recovery screen visual QA

## Review target

- **Source visual:** user supplied `07d1ac43-3c76-4e6e-a967-c3db32547804.png` (the 13 / Modular Bridge concept), 1672 × 941 px.
- **Implementation:** `app/connection-recovery.tsx` and `app/connection-recovery.module.css`.
- **Target viewport:** 1672 × 941 CSS px, device pixel ratio 1.
- **Reviewed state:** initial unavailable state (`data-connection-mode="unavailable"`). The restoring-session and verified-session redirect states use the same animated composition with state-specific copy.
- **Static evidence:** the source image was composited with the generated object-free room plate during review. This evidence checks object placement and cropping; it is not required at runtime.

## Findings

### Resolved

- The five photographed blocks are preserved as three moving groups: the left red/ivory pair, the right ivory/red pair, and the floating red bridge block. This keeps the exact rounded edges, highlights, shadows, and material texture from the supplied concept.
- The room and plinths are supplied by `public/connection-recovery/modular-bridge-room.png`, so the background does not duplicate the blocks or any old UI copy.
- The left recovery panel is a real glass surface: translucent warm-white fill, 22 px backdrop blur, saturation, a white edge, inset highlight, and a soft wine-tinted shadow. A solid fallback is included for browsers without `backdrop-filter`.
- The animated groups move with transforms only, include a visible pause/resume control, and stop under `prefers-reduced-motion`.
- Focus-visible outlines, status live-region semantics, a 44 px help target, and a 44 px motion-control target are retained for keyboard and touch use.

### Browser verification

- The headless Playwright review captured desktop and mobile recovery states and exercised retry, pause/resume, connection help, reduced-motion and overflow checks. It also verified the authenticated CEO dashboard and read-only visitor occupancy flow. The captures are review artifacts outside the source archive.

### Source reconstruction note

- The object-free room plate reconstructs the portions of the stone surfaces that were hidden by the original blocks. The visible scene, block crops, lighting direction, seam, and plinth geometry are aligned to the supplied concept; the hidden grain is not an original source pixel.

## Final result

Implemented and Chromium-verified (Firefox/WebKit checks remain pending in CI): the recovery composition is wired to the production lock, session restoration and workspace retry states, with the source assets included in the full archive.

The 15 September regression review also fixed early pre-hydration clicks by keeping
interactive controls disabled until their handlers are attached. Actual changing
transforms, pause/resume and reduced motion pass desktop/mobile Chromium tests.
