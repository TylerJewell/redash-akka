import { identity, isFunction, isString, map } from "lodash";
import { ItemsSource } from "./ItemsSource";
import { subscribeToList } from "@/services/stream";

// An items source fed by a stream instead of by a fetch.
//
// This is the whole of what RENDERING.md R1 changes on a list page. `ResourceItemsSource`
// calls an endpoint every time the page, the sort, the search term or the tags move, and
// learns about a change made elsewhere only when one of those moves. This one opens a
// single connection, keeps the latest frame, and redraws when a new frame arrives; the
// sorting, paging, searching and tag filtering the page already asked for then happen over
// that frame, in `PlainListFetcher`, exactly as they already do for a plain list.
//
// R4's test - delete the adapter and see whether the view still works - is answered by
// construction: there is no fetch underneath. Take the subscription away and `current()`
// answers null forever and the page never leaves its loading state.
//
// Two consequences are behavioural rather than cosmetic and are in the README's
// differences list. Searching and ordering happen in the browser over the whole visible
// set rather than in the server over the whole table, so a term matches the fields named in
// `searchFields` rather than ranking by PostgreSQL's full-text index; and the count a pager
// shows is the number of rows the caller can see rather than a count the database made.
export class StreamItemsSource extends ItemsSource {
  constructor({ getPath, getItemProcessor, searchFields = ["name"], ...rest }) {
    const processItem = isFunction(getItemProcessor) ? getItemProcessor : () => identity;

    super({
      ...rest,
      isPlainList: true,
      doRequest: (request, context) => {
        // The subscription opens on the first request rather than in the constructor,
        // because which stream this page wants is decided by a prop the constructor is not
        // given - "My Queries" and "Archive" are the same page over two different sets.
        this._open(getPath(context));
        const items = this._subscription.current();
        if (items === null) {
          // The first frame has not arrived. Answering an empty list here would draw the
          // empty state and then replace it; waiting draws the loading state the page has.
          return this._firstFrame.then((frame) => this._filter(frame, request));
        }
        return Promise.resolve(this._filter(items, request));
      },
      processResults: (results, context) => {
        const process = processItem(context);
        return map(results, (item) => (isFunction(process) ? process(item, context) : item));
      },
    });

    this._searchFields = searchFields;
    this._subscription = null;
  }

  _open(path) {
    if (this._subscription && this._path === path) {
      return;
    }
    if (this._subscription) {
      this._subscription.close();
    }
    this._path = path;
    let resolveFirstFrame;
    this._firstFrame = new Promise((resolve) => {
      resolveFirstFrame = resolve;
    });
    this._subscription = subscribeToList(path, (items) => {
      if (resolveFirstFrame) {
        resolveFirstFrame(items);
        resolveFirstFrame = null;
      } else {
        this.update();
      }
    });
  }

  // The search term and the tag list the page passed down, applied to the frame.
  _filter(items, request) {
    let out = items;
    const term = isString(request?.q) ? request.q.toLowerCase() : "";
    if (term !== "") {
      out = out.filter((item) =>
        this._searchFields.some((field) => String(item[field] || "").toLowerCase().includes(term))
      );
    }
    const tags = request?.tags || [];
    if (tags.length > 0) {
      out = out.filter((item) => tags.every((tag) => (item.tags || []).includes(tag)));
    }
    return out;
  }

  close() {
    if (this._subscription) {
      this._subscription.close();
      this._subscription = null;
    }
  }
}
