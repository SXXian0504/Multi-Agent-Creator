// @ts-ignore
/* eslint-disable */
import request from '@/request'

export async function createKnowledgeBase(
  body: API.KnowledgeBaseCreateRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseKnowledgeBaseVO>('/knowledge-base/create', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    ...(options || {}),
  })
}

export async function listKnowledgeBases(options?: { [key: string]: any }) {
  return request<API.BaseResponseListKnowledgeBaseVO>('/knowledge-base/list', {
    method: 'GET',
    ...(options || {}),
  })
}

export async function listKnowledgeDocuments(
  params: { knowledgeBaseId: number },
  options?: { [key: string]: any }
) {
  const { knowledgeBaseId } = params
  return request<API.BaseResponseListKnowledgeDocumentVO>(`/knowledge-base/${knowledgeBaseId}/documents`, {
    method: 'GET',
    ...(options || {}),
  })
}

export async function uploadKnowledgeDocument(
  params: { knowledgeBaseId: number; file: File },
  options?: { [key: string]: any }
) {
  const formData = new FormData()
  formData.append('file', params.file)
  return request<API.BaseResponseKnowledgeDocumentVO>(`/knowledge-base/${params.knowledgeBaseId}/upload`, {
    method: 'POST',
    data: formData,
    ...(options || {}),
  })
}
