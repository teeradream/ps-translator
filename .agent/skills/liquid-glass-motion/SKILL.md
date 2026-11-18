---
name: liquid-glass-motion
description: Use when building UI components, drawers, modals, or buttons that require Apple-style Liquid Glass translucency paired with tactile spring physics, drag momentum, and press compression.
---

# Liquid Glass & Tactile Spring Physics Guidelines

## 1. Liquid Glass Visual Surface (Tailwind CSS)
Every floating surface, drawer, or interactive pill must use multi-layered translucency rather than flat colors:
- **Backdrop Blur & Saturation:** Always combine `backdrop-blur-2xl` with `backdrop-saturate-150` to refract background colors naturally.
- **Layered Tint:** Use ultra-low opacity fills (`bg-white/15 dark:bg-neutral-900/40`) paired with subtle inner specular highlights.
- **Specular Rim Border:** Apply a thin, light-catching border (`border border-white/30 dark:border-white/15`) and a soft inner top glow (`shadow-[inset_0_1px_1px_rgba(255,255,255,0.45)]`).

## 2. Tactile Button Compression (Press Physics)
Buttons must feel like physical, slightly soft gel/glass capsules when tapped or clicked:
- **Library:** Use `motion/react` (install `motion` if missing).
- **Press & Hover States:**
  - `whileHover={{ scale: 1.015 }}`
  - `whileTap={{ scale: 0.94 }}` (noticeable inward squish on press)
- **Spring Config:** Use high stiffness with controlled damping so it snaps back crisply without wobbling excessively:
  `transition={{ type: "spring", stiffness: 520, damping: 24, mass: 0.7 }}`

## 3. Drawer & Sheet Drag Momentum (Velocity-Aware)
When sliding a drawer or bottom sheet, child buttons and elements inside must react to the drag velocity and spring tension:
- **Drawer Drag Physics:**
  - Enable `drag="y"` (or `"x"` for side drawers) with `dragConstraints={{ top: 0 }}` and `dragElastic={0.18}` (rubber-band resistance when pulled past limits).
  - Dismiss threshold: Close if `info.offset.y > 120 || info.velocity.y > 500`.
- **Follow-Through Bounce on Child Elements:**
  - Bind a `useMotionValue(0)` to the drawer's drag axis, pass it through `useSpring(..., { stiffness: 350, damping: 22, mass: 0.9 })`, and map it via `useTransform` to child button wrappers so buttons subtly bounce with inertia after the drawer moves or settles.
- **Performance Rule:** Animate ONLY GPU-composited properties (`transform`, `opacity`). Never animate `backdrop-filter` blur radius dynamically during drag.

---

## 4. Next.js, Tailwind CSS & `motion/react` Setup

### Installation & Directives
- Install `motion` if not already present in `package.json`:
  ```bash
  npm install motion
  ```
- In **Next.js App Router**, any component using `motion/react` hooks (`useMotionValue`, `useSpring`, `useTransform`) or `<motion.*>` elements **must** include `"use client";` at the top of the file.
- Import from `motion/react` (not `framer-motion`):
  ```tsx
  import {
    motion,
    AnimatePresence,
    useMotionValue,
    useSpring,
    useTransform,
    type PanInfo,
  } from "motion/react";
  ```

### Tailwind CSS Class Presets
Combine these utility classes for consistent Liquid Glass surfaces across light and dark modes:

```tsx
const LIQUID_GLASS_SURFACE =
  "backdrop-blur-2xl backdrop-saturate-150 bg-white/15 dark:bg-neutral-900/40 border border-white/30 dark:border-white/15 shadow-[inset_0_1px_1px_rgba(255,255,255,0.45)]";
```

### Reference Implementation (Next.js + Tailwind CSS + `motion/react`)

#### Tactile Liquid Glass Button
```tsx
"use client";

import { motion, type HTMLMotionProps } from "motion/react";

type LiquidGlassButtonProps = HTMLMotionProps<"button">;

export function LiquidGlassButton({
  className = "",
  children,
  ...props
}: LiquidGlassButtonProps) {
  return (
    <motion.button
      whileHover={{ scale: 1.015 }}
      whileTap={{ scale: 0.94 }}
      transition={{ type: "spring", stiffness: 520, damping: 24, mass: 0.7 }}
      className={`rounded-full px-5 py-2.5 backdrop-blur-2xl backdrop-saturate-150 bg-white/15 dark:bg-neutral-900/40 border border-white/30 dark:border-white/15 shadow-[inset_0_1px_1px_rgba(255,255,255,0.45)] select-none ${className}`}
      {...props}
    >
      {children}
    </motion.button>
  );
}
```

#### Velocity-Aware Liquid Glass Bottom Sheet
```tsx
"use client";

import {
  AnimatePresence,
  motion,
  useMotionValue,
  useSpring,
  useTransform,
  type PanInfo,
} from "motion/react";

interface LiquidGlassSheetProps {
  open: boolean;
  onClose: () => void;
  children: React.ReactNode;
}

export function LiquidGlassSheet({
  open,
  onClose,
  children,
}: LiquidGlassSheetProps) {
  const dragY = useMotionValue(0);
  const smoothY = useSpring(dragY, { stiffness: 350, damping: 22, mass: 0.9 });
  const childFollowThroughY = useTransform(smoothY, (latest) => latest * 0.12);

  const handleDragEnd = (_: MouseEvent | TouchEvent | PointerEvent, info: PanInfo) => {
    if (info.offset.y > 120 || info.velocity.y > 500) {
      onClose();
    }
  };

  return (
    <AnimatePresence>
      {open && (
        <motion.div
          className="fixed inset-0 z-50 flex items-end justify-center bg-black/30"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          onClick={onClose}
        >
          <motion.div
            drag="y"
            dragConstraints={{ top: 0 }}
            dragElastic={0.18}
            style={{ y: dragY }}
            onDragEnd={handleDragEnd}
            onClick={(e) => e.stopPropagation()}
            initial={{ y: "100%" }}
            animate={{ y: 0 }}
            exit={{ y: "100%" }}
            transition={{ type: "spring", stiffness: 420, damping: 32, mass: 0.85 }}
            className="w-full max-w-lg rounded-t-3xl p-6 backdrop-blur-2xl backdrop-saturate-150 bg-white/15 dark:bg-neutral-900/40 border border-white/30 dark:border-white/15 shadow-[inset_0_1px_1px_rgba(255,255,255,0.45)]"
          >
            <div className="mx-auto mb-4 h-1.5 w-12 rounded-full bg-white/40" />
            <motion.div style={{ y: childFollowThroughY }}>
              {children}
            </motion.div>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
```
