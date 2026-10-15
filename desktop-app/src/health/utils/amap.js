/**
 * 高德地图 Web API 封装（走后端代理，避免浏览器拦截）
 * 文档: https://lbs.amap.com/api/webservice/summary
 */

import { API_BASE_URL as API_BASE } from '../../api/baseUrl';

/**
 * 通用请求封装（走后端代理，key 在服务端）
 */
async function request(endpoint, params = {}) {
  const url = new URL(`${API_BASE}/api/desktop/amap/proxy`, window.location.origin);
  // 现有健康模块使用 /ip 等短路径，后端公开代理接收固定 v3/ 路径。
  const normalized = endpoint.replace(/^\/+/, '');
  url.searchParams.set('path', normalized.startsWith('v3/') ? normalized : `v3/${normalized}`);
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') url.searchParams.set(k, v);
  });
  try {
    const res = await fetch(url.toString());
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();
    if (data.status !== '1') throw new Error(data.info || '高德 API 返回错误');
    return { success: true, data };
  } catch (err) {
    console.error('[AMap]', err.message);
    return { success: false, error: err.message };
  }
}

/**
 * IP 定位（获取当前城市和粗略坐标）
 */
export async function ipLocation() {
  const result = await request('/ip');
  if (result.success && result.data.rectangle) {
    const [lng1, lat1, lng2, lat2] = result.data.rectangle.split(';')[0].split(',');
    return {
      success: true,
      city: result.data.city,
      lng: (parseFloat(lng1) + parseFloat(lng2)) / 2,
      lat: (parseFloat(lat1) + parseFloat(lat2)) / 2,
    };
  }
  return result;
}

/**
 * 周边搜索（医院、诊所、药店等）
 * @param {number} lng - 经度
 * @param {number} lat - 纬度
 * @param {string} keywords - 关键词（医院|诊所|药店）
 * @param {number} radius - 搜索半径（米），默认 3000
 * @param {number} offset - 每页条数，默认 20
 * @param {number} page - 页码
 */
export async function searchAround(lng, lat, keywords = '医院', radius = 3000, offset = 20, page = 1) {
  const result = await request('/place/around', {
    location: `${lng},${lat}`,
    keywords,
    radius,
    offset,
    page,
    extensions: 'all',
  });
  if (result.success) {
    return {
      success: true,
      count: parseInt(result.data.count),
      pois: (result.data.pois || []).map(p => ({
        id: p.id,
        name: p.name,
        type: p.type,
        address: p.address,
        lng: p.location?.split(',')[0],
        lat: p.location?.split(',')[1],
        distance: parseInt(p.distance),
        phone: p.tel || p.biz_ext?.tel || '',
        rating: p.biz_ext?.rating || '',
      })),
    };
  }
  return result;
}

/**
 * 地理编码（地址 → 坐标）
 */
export async function geoCode(address) {
  const result = await request('/geocode/geo', { address });
  if (result.success && result.data.geocodes?.length > 0) {
    const g = result.data.geocodes[0];
    return {
      success: true,
      lng: parseFloat(g.location.split(',')[0]),
      lat: parseFloat(g.location.split(',')[1]),
      formatted: g.formatted_address,
    };
  }
  return result;
}

/**
 * 逆地理编码（坐标 → 地址）
 */
export async function reGeoCode(lng, lat) {
  const result = await request('/geocode/regeo', { location: `${lng},${lat}` });
  if (result.success && result.data.regeocode) {
    return { success: true, address: result.data.regeocode.formatted_address };
  }
  return result;
}

/**
 * 驾车路线规划
 */
export async function drivingRoute(origin, destination) {
  const result = await request('/direction/driving', { origin, destination });
  if (result.success && result.data.route?.paths?.length > 0) {
    const p = result.data.route.paths[0];
    return { success: true, distance: parseInt(p.distance), duration: parseInt(p.duration), steps: p.steps };
  }
  return result;
}

/**
 * 步行路线规划
 */
export async function walkingRoute(origin, destination) {
  const result = await request('/direction/walking', { origin, destination });
  if (result.success && result.data.route?.paths?.length > 0) {
    const p = result.data.route.paths[0];
    return { success: true, distance: parseInt(p.distance), duration: parseInt(p.duration), steps: p.steps };
  }
  return result;
}

/**
 * 公交路线规划
 */
export async function transitRoute(origin, destination, city) {
  const result = await request('/direction/transit/integrated', { origin, destination, city });
  if (result.success && result.data.route?.transits?.length > 0) {
    return { success: true, distance: parseInt(result.data.route.distance), duration: parseInt(result.data.route.duration), transits: result.data.route.transits };
  }
  return result;
}

/**
 * POI 搜索
 */
export async function searchPoi(keywords, city) {
  const result = await request('/place/text', { keywords, city });
  if (result.success) {
    return {
      success: true,
      count: parseInt(result.data.count),
      pois: (result.data.pois || []).map(p => ({ id: p.id, name: p.name, address: p.address, lng: p.location?.split(',')[0], lat: p.location?.split(',')[1] })),
    };
  }
  return result;
}

/**
 * 距离测量
 */
export async function measureDistance(origins, destination) {
  const result = await request('/distance', { origins, destination, type: '1' });
  if (result.success && result.data.results?.length > 0) {
    return { success: true, distance: parseInt(result.data.results[0].distance) };
  }
  return result;
}

/**
 * 动态设置 Key（兼容旧调但实际走后端不再需要）
 */
export function setAmapKey(key) {
  // 不再使用前端 key，保留接口兼容
}
