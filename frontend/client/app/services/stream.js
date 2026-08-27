// One server-sent-event connection per view, and what a view does with it.
//
// redash asks again on a timer: every list page fetches when it mounts and refetches when
// the person changes a page, a sort or a search term, and learns about a change made
// somewhere else only when something makes it fetch. This rebuild sends the list down a
// stream instead - RENDERING.md R1 - and these two functions are the whole of the data
// layer that changes.
//
// The first frame carries the current state, so the first render needs no second round
// trip. A dropped connection is reopened by the browser and answered with a fresh first
// frame, so the view converges rather than resuming from a position it would have to
// remember. There is no cache underneath this that polls: `subscribe` opens an EventSource
// and nothing else fetches, so deleting the subscription leaves the view with no data at
// all.

export function subscribe(path, onData) {
  const source = new EventSource(path);
  source.onmessage = (event) => {
    if (event.data === "") {
      return; // a keep-alive, sent so an idle connection is not closed for being idle
    }
    onData(JSON.parse(event.data));
  };
  return () => source.close();
}

// A list that stays open: the latest frame is kept so a caller asking for "the items now"
// gets them without a request, and every new frame tells the caller to redraw.
export function subscribeToList(path, onChange) {
  let latest = null;
  const close = subscribe(path, (items) => {
    latest = items;
    onChange(items);
  });
  return {
    current: () => latest,
    close,
  };
}
