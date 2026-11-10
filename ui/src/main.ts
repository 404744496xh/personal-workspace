import { createApp } from 'vue'
import App from './App.vue'
import { watchColorScheme } from './theme'
import './styles.css'

createApp(App).mount('#workspace-app')
watchColorScheme()
