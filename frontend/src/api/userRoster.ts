export const ROSTER_HEADER = '操作,登录账号,姓名,机构编码,账号类别,角色编码'
export const ROSTER_MAX_BYTES = 256 * 1024

export interface RosterRow {
  action: 'CREATE' | 'CLASSIFY'
  username: string
  realName: string
  departmentCode: string
  accountCategory: 'DISTRICT' | 'BUREAU' | 'AGENCY'
  roleCodes?: string[]
}

export interface RosterPreview {
  row: number
  username: string
  status: 'READY' | 'ERROR'
  message: string
}

export interface RosterResult {
  row: number
  username: string
  initialPassword?: string
}

function csvCells(text: string): string[][] {
  const rows: string[][] = []
  let row: string[] = []
  let i = text.charCodeAt(0) === 0xfeff ? 1 : 0
  while (i <= text.length) {
    let value = ''
    if (text[i] === '"') {
      i++
      while (true) {
        const quote = text.indexOf('"', i)
        if (quote === -1) throw new Error('CSV 引号未闭合')
        value += text.slice(i, quote)
        i = quote + 1
        if (text[i] !== '"') break
        value += '"'
        i++
      }
    } else {
      const start = i
      while (i < text.length && !',\r\n"'.includes(text[i])) i++
      value = text.slice(start, i)
    }
    row.push(value)
    const delimiter = text[i++]
    if (delimiter === ',') continue
    if (delimiter !== undefined && delimiter !== '\n' && delimiter !== '\r') {
      throw new Error('CSV 引号格式错误，字段中的双引号需成对转义')
    }
    rows.push(row)
    row = []
    if (delimiter === '\r' && text[i] === '\n') i++
  }
  while (rows.at(-1)?.length === 1 && rows.at(-1)?.[0] === '') rows.pop()
  return rows
}

export function parseUserRoster(text: string): RosterRow[] {
  const [header, ...rows] = csvCells(text)
  const columns = ROSTER_HEADER.split(',')
  if (header?.length !== columns.length || !columns.every((column, i) => header[i] === column)) {
    throw new Error('CSV 表头须为 ' + ROSTER_HEADER)
  }
  if (!rows.length || rows.length > 100) throw new Error('每次须上传 1 至 100 条账号记录')
  return rows.map((cells, index) => {
    const row = index + 1
    if (cells.length !== columns.length) throw new Error(`第 ${row} 条记录须有 6 列`)
    const [action, username, realName, departmentCode, accountCategory, roleText] = cells.map(cell => cell.trim())
    if (action !== 'CREATE' && action !== 'CLASSIFY') throw new Error(`第 ${row} 条记录操作须为 CREATE 或 CLASSIFY`)
    if (accountCategory !== 'DISTRICT' && accountCategory !== 'BUREAU' && accountCategory !== 'AGENCY') {
      throw new Error(`第 ${row} 条记录账号类别须为 DISTRICT、BUREAU 或 AGENCY`)
    }
    if (!username || !realName || !departmentCode) throw new Error(`第 ${row} 条记录须填写登录账号、姓名和机构编码`)
    if (action === 'CLASSIFY') {
      if (roleText) throw new Error(`第 ${row} 条记录为归类操作，角色编码须留空`)
      return { action, username, realName, departmentCode, accountCategory }
    }
    const roleCodes = roleText.split('|').map(role => role.trim())
    if (roleCodes.some(role => !role)) {
      throw new Error(`第 ${row} 条记录为新建操作，须填写角色编码，多个角色使用 | 分隔`)
    }
    return { action, username, realName, departmentCode, accountCategory, roleCodes }
  })
}