import { axios } from "@/services/axios";
import { merge } from "lodash";

// backwards compatibility
const normalizeCondition = {
  "greater than": ">",
  "less than": "<",
  equals: "=",
};

const transformResponse = (data) =>
  merge({}, data, {
    options: {
      op: normalizeCondition[data.options.op] || data.options.op,
    },
  });

const transformRequest = (data) => {
  const newData = Object.assign({}, data);
  if (newData.query_id === undefined) {
    newData.query_id = newData.query.id;
    newData.destination_id = newData.destinations;
    delete newData.query;
    delete newData.destinations;
  }

  return newData;
};

const saveOrCreateUrl = (data) => (data.id ? `api/alerts/${data.id}` : "api/alerts");

// The alerts list is fed by a stream rather than by a fetch. `subscribe` opens one
// EventSource and hands every frame to the caller; the first frame carries the current
// state, so the first render needs no separate round trip, and a reconnect gets a fresh
// first frame rather than a gap. RENDERING.md R1, and SPEC-001 R25 for what the reconnect
// is defined to do.
//
// There is no adapter underneath this that polls: delete the subscription and the list has
// no other route to its data.
const subscribe = (onAlerts) => {
  const source = new EventSource("api/streams/alerts");
  source.onmessage = (event) => onAlerts(JSON.parse(event.data));
  return () => source.close();
};

const Alert = {
  subscribe,
  query: () => axios.get("api/alerts"),
  get: ({ id }) => axios.get(`api/alerts/${id}`).then(transformResponse),
  save: (data) => axios.post(saveOrCreateUrl(data), transformRequest(data)),
  delete: (data) => axios.delete(`api/alerts/${data.id}`),
  mute: (data) => axios.post(`api/alerts/${data.id}/mute`),
  unmute: (data) => axios.delete(`api/alerts/${data.id}/mute`),
  evaluate: (data) => axios.post(`api/alerts/${data.id}/eval`),
};

export default Alert;
