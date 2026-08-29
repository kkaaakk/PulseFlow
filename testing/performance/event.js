// Event payload helper based on com.pulseflow.common.dto.EventRequest.
export const EVENT_TYPES = [
  'LOGIN',
  'CONTENT_VIEW',
  'SEARCH',
  'LIKE',
  'FAVORITE',
  'ADD_CART',
  'REMOVE_CART',
  'ORDER_CREATE',
  'ORDER_PAID',
  'SHARE',
  'CLICK',
];

const EVENT_WEIGHTS = [2, 36, 12, 12, 8, 10, 4, 5, 5, 4, 2];
const runId = (__ENV.RUN_ID || 'adhoc').replace(/[^a-zA-Z0-9_-]/g, '-');

function weightedEventType() {
  const total = EVENT_WEIGHTS.reduce((sum, weight) => sum + weight, 0);
  let value = Math.floor(Math.random() * total);
  for (let index = 0; index < EVENT_TYPES.length; index += 1) {
    if (value < EVENT_WEIGHTS[index]) return EVENT_TYPES[index];
    value -= EVENT_WEIGHTS[index];
  }
  return EVENT_TYPES[EVENT_TYPES.length - 1];
}

function eventTimeString() {
  // application.yml uses Asia/Shanghai in the local workflow. Allow callers
  // to override the wall-clock offset when the app runs in another timezone.
  const offsetHours = Number(__ENV.EVENT_TIME_OFFSET_HOURS || 8);
  return new Date(Date.now() + offsetHours * 60 * 60 * 1000)
    .toISOString()
    .slice(0, 19);
}

function randomProperties(eventType, sequence) {
  const properties = { category: `k6-${sequence % 8}` };
  if (eventType === 'CONTENT_VIEW' || eventType === 'SEARCH') {
    properties.duration = 1000 + (sequence % 60000);
  }
  if (eventType === 'ADD_CART' || eventType === 'REMOVE_CART') {
    properties.cartItemId = `K6-SKU-${sequence % 100}`;
    properties.price = Number((9.99 + (sequence % 500)).toFixed(2));
  }
  if (eventType === 'ORDER_CREATE' || eventType === 'ORDER_PAID') {
    properties.orderId = `k6-order-${__VU}-${__ITER}-${sequence}`;
    properties.price = Number((29.9 + (sequence % 1000)).toFixed(2));
  }
  if (eventType === 'CLICK') {
    properties.taskId = 700000 + (sequence % 1000);
    properties.campaignId = 710000 + (sequence % 20);
  }
  return properties;
}

export function isAccepted(response) {
  let body = null;
  try {
    body = JSON.parse(response.body);
  } catch (error) {
    return false;
  }
  return response.status === 200
    && body.code === 200
    && body.data !== null
    && body.data.accepted === true;
}

export function makeEvent(sequence) {
  const eventType = weightedEventType();
  const eventId = `k6-${runId}-${__VU}-${__ITER}-${Date.now()}-${sequence}`;
  return {
    eventId,
    userId: 8000000 + (((__VU - 1) * 10000 + __ITER) % 1000000),
    eventType,
    targetId: eventType === 'LOGIN' || eventType === 'SEARCH' ? null : 900000 + (sequence % 10000),
    eventTime: eventTimeString(),
    properties: randomProperties(eventType, sequence),
  };
}
