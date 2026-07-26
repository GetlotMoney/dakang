/**
 * 字典查询工具函数
 *
 * 提供统一的字典数据获取方式，避免在每个模块重复定义。
 *
 * @module utils/dict
 */
import request from '@/utils/http'
import type { DictData, DictType } from '@/constants/dict'

/**
 * 批量获取字典数据
 * @param dictTypeList - 字典类型值数组，如 ['20', '41', '42']
 * @returns 字典类型列表
 */
export function fetchDictByTypes(dictTypeList: string[]): Promise<DictType[]> {
  return request.post<DictType[]>({
    url: '/api/dict/listByType',
    data: dictTypeList
  })
}

/**
 * 获取单个字典类型的选项列表（用于下拉选择）
 *
 * 注意：接口返回的是 DictType 包装数组（含 dictDataList），必须解包后返回，
 * 否则 toDictOptions 拿到的 label/value 全为 undefined（曾致全站字典下拉空白）。
 * @param dictType - 字典类型值
 * @returns 字典数据列表
 */
export async function fetchDictOptions(dictType: string): Promise<DictData[]> {
  const types = await fetchDictByTypes([dictType])
  return types.find((t) => t.dictType === dictType)?.dictDataList || []
}

/**
 * 根据字典值查找标签
 * @param dictList - 字典数据列表
 * @param value - 字典值
 * @returns 字典标签，未找到返回空字符串
 */
export function findDictLabel(dictList: DictData[], value: number | string): string {
  return dictList.find((d) => d.dictValue === value)?.dictLabel || ''
}

/**
 * 将字典数据转换为 El-Select 可用的 options 格式
 * @param dictList - 字典数据列表
 * @returns { label, value } 格式的选项数组
 */
export function toDictOptions(dictList: DictData[]): { label: string; value: number }[] {
  return dictList.map((d) => ({ label: d.dictLabel, value: d.dictValue }))
}
