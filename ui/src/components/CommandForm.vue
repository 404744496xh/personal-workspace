<script setup lang="ts">
import { ref } from 'vue'
import type { QuickCmd, QuickCmdPayload } from '../types'
import Modal from './ui/Modal.vue'
import Button from './ui/Button.vue'
import Input from './ui/Input.vue'
import Textarea from './ui/Textarea.vue'

const props = defineProps<{
  command: QuickCmd | null
  categories: string[]
  saving: boolean
}>()

const emit = defineEmits<{
  save: [payload: QuickCmdPayload]
  close: []
}>()

const title = ref(props.command?.title ?? '')
const category = ref(props.command?.category ?? '')
const commandText = ref(props.command?.command ?? '')
const note = ref(props.command?.note ?? '')
const localError = ref('')

function close(): void {
  if (!props.saving) emit('close')
}

function submit(): void {
  const titleValue = title.value.trim()
  const commandValue = commandText.value.trim()
  if (!titleValue) {
    localError.value = '请输入命令名称'
    return
  }
  if (!commandValue) {
    localError.value = '请输入命令内容'
    return
  }
  localError.value = ''
  emit('save', {
    title: titleValue,
    command: commandValue,
    category: category.value.trim() || null,
    note: note.value.trim() || null,
    tags: props.command?.tags ?? []
  })
}
</script>

<template>
  <Modal :title="command ? '编辑命令' : '新建命令'" @close="close">
    <form class="ws-form flex flex-col gap-4" @submit.prevent="submit">
      <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <label class="ws-field flex flex-col gap-1.5">
          <span class="text-[12px] font-medium text-[#6e6e73] dark:text-[#98989d]">名称</span>
          <Input v-model="title" autofocus maxlength="200" autocomplete="off" placeholder="如：查看端口占用" />
        </label>
        <label class="ws-field flex flex-col gap-1.5">
          <span class="text-[12px] font-medium text-[#6e6e73] dark:text-[#98989d]">分类</span>
          <Input
            v-model="category"
            maxlength="32"
            list="ws-command-categories"
            autocomplete="off"
            placeholder="如：Linux"
          />
          <datalist id="ws-command-categories">
            <option v-for="item in categories" :key="item" :value="item"></option>
          </datalist>
        </label>
      </div>

      <label class="ws-field flex flex-col gap-1.5">
        <span class="text-[12px] font-medium text-[#6e6e73] dark:text-[#98989d]">命令</span>
        <Textarea
          v-model="commandText"
          class="font-mono text-[13px]"
          rows="6"
          maxlength="5000"
          placeholder="ss -lntp"
        ></Textarea>
      </label>

      <label class="ws-field flex flex-col gap-1.5">
        <span class="text-[12px] font-medium text-[#6e6e73] dark:text-[#98989d]">备注</span>
        <Textarea v-model="note" rows="3" maxlength="2000" placeholder="使用场景、参数说明"></Textarea>
      </label>

      <p
        v-if="localError"
        class="ws-form-error rounded-lg bg-[#FF3B30]/[0.08] px-3 py-2 text-[12px] text-[#FF3B30]"
        role="alert"
      >{{ localError }}</p>

      <footer class="ws-modal-footer mt-1 flex justify-end gap-2">
        <Button type="button" variant="ghost" :disabled="saving" @click="close">取消</Button>
        <Button type="submit" variant="primary" :disabled="saving">
          {{ saving ? '保存中…' : '保存' }}
        </Button>
      </footer>
    </form>
  </Modal>
</template>
