import axios from 'axios'

const baseURL = window.location.protocol === 'file:'
  ? 'http://127.0.0.1:8080/api'
  : '/api'

export const api = axios.create({
  baseURL,
  timeout: 30000,
})

api.interceptors.response.use(
  response => response.data?.data ?? response.data,
  error => Promise.reject(
    new Error(error.response?.data?.message ?? error.message ?? '请求失败')
  ),
)
