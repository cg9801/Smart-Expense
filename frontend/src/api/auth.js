import { get, post } from './request.js'

export function login(username, password) {
  return post('/api/v1/auth/login', { username, password })
}

export function fetchMe() {
  return get('/api/v1/auth/me')
}
