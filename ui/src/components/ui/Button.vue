<script setup lang="ts">
import { computed } from 'vue'
import { cva, type VariantProps } from 'class-variance-authority'
import { cn } from '../../lib/utils'

const variants = cva(
  'inline-flex min-h-11 select-none items-center justify-center gap-1.5 rounded-full px-4 text-[13px] font-medium leading-none transition-[background-color,color,opacity,transform] duration-200 ease-[var(--ws-ease)] focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-[#007AFF]/25 disabled:pointer-events-none disabled:opacity-40',
  {
    variants: {
      variant: {
        primary:
          'bg-[#007AFF] text-white hover:bg-[#0071EB] active:bg-[#0063CC] dark:bg-[#0A84FF] dark:hover:bg-[#2E96FF]',
        secondary:
          'bg-black/[0.05] text-[#1d1d1f] hover:bg-black/[0.08] dark:bg-white/[0.08] dark:text-[#f5f5f7] dark:hover:bg-white/[0.13]',
        ghost:
          'bg-transparent text-[#6e6e73] hover:bg-black/[0.05] hover:text-[#1d1d1f] dark:text-[#98989d] dark:hover:bg-white/[0.08] dark:hover:text-[#f5f5f7]',
        danger: 'bg-[#FF3B30] text-white hover:bg-[#F0332A]'
      },
      size: {
        default: 'min-h-11 px-4',
        sm: 'min-h-11 px-3 text-[12px]',
        icon: 'h-11 w-11 min-h-11 p-0'
      }
    },
    defaultVariants: {
      variant: 'secondary',
      size: 'default'
    }
  }
)

type ButtonVariant = VariantProps<typeof variants>

const props = withDefaults(defineProps<{
  variant?: ButtonVariant['variant']
  size?: ButtonVariant['size']
  class?: string
}>(), {
  variant: 'secondary',
  size: 'default'
})

const classes = computed(() => cn(variants({ variant: props.variant, size: props.size }), props.class))
</script>

<template>
  <button :class="classes">
    <slot />
  </button>
</template>
