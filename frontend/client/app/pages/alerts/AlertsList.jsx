import { toUpper } from "lodash";
import React from "react";
import routeWithUserSession from "@/components/ApplicationArea/routeWithUserSession";
import Link from "@/components/Link";
import PageHeader from "@/components/PageHeader";
import Paginator from "@/components/Paginator";
import EmptyState, { EmptyStateHelpMessage } from "@/components/empty-state/EmptyState";
import { orderBy } from "lodash";
import DynamicComponent from "@/components/DynamicComponent";

import ItemsTable, { Columns } from "@/components/items-list/components/ItemsTable";

import Alert from "@/services/alert";
import { currentUser } from "@/services/auth";
import routes from "@/services/routes";

export const STATE_CLASS = {
  unknown: "label-warning",
  ok: "label-success",
  triggered: "label-danger",
};

class AlertsList extends React.Component {
  // The list is fed by a stream: one subscription, opened on mount and closed on unmount,
  // whose first frame carries the current state. Nothing here asks again on a timer, and
  // there is no fetch underneath it to fall back to - the items-list controller the
  // original used has been removed rather than left in place resolving an empty list,
  // because a controller that still fetches is the adapter RENDERING.md R4 rejects.
  //
  // The sorting and pagination the controller used to hold are local state here, with the
  // same defaults the original configured: newest created first, twenty to a page.
  state = { alerts: null, orderByField: "created_at", orderByReverse: true, page: 1, itemsPerPage: 20 };

  componentDidMount() {
    this.unsubscribe = Alert.subscribe((alerts) => this.setState({ alerts }));
  }

  componentWillUnmount() {
    if (this.unsubscribe) {
      this.unsubscribe();
    }
  }

  listColumns = [
    Columns.custom.sortable(
      (text, alert) => (
        <span title={alert.options.muted ? "Muted" : "Active"}>
          <i className={`fa fa-bell-${alert.options.muted ? "slash" : "o"} p-r-0`} aria-hidden="true" />
          <span className="sr-only">{alert.options.muted ? "Muted" : "Active"}</span>
        </span>
      ),
      {
        title: (
          <>
            <i className="fa fa-bell p-r-0" aria-hidden="true" />
            <span className="sr-only">Sort by notification status.</span>
          </>
        ),
        field: "muted",
        width: "1%",
      }
    ),
    Columns.custom.sortable(
      (text, alert) => (
        <div>
          <Link className="table-main-title" href={"alerts/" + alert.id}>
            {alert.name}
          </Link>
        </div>
      ),
      {
        title: "Name",
        field: "name",
      }
    ),
    Columns.custom((text, item) => item.user.name, { title: "Created By", width: "1%" }),
    Columns.custom.sortable(
      (text, alert) => (
        <div>
          <span className={`label ${STATE_CLASS[alert.state]}`}>{toUpper(alert.state)}</span>
        </div>
      ),
      {
        title: "State",
        field: "state",
        width: "1%",
        className: "text-nowrap",
      }
    ),
    Columns.timeAgo.sortable({ title: "Last Updated At", field: "updated_at", width: "1%" }),
    Columns.dateTime.sortable({ title: "Created At", field: "created_at", width: "1%" }),
  ];

  toggleSorting = (orderByField) => {
    this.setState((state) => ({
      orderByField,
      orderByReverse: state.orderByField === orderByField ? !state.orderByReverse : false,
    }));
  };

  render() {
    const { alerts, orderByField, orderByReverse, page, itemsPerPage } = this.state;
    const isLoaded = alerts !== null;
    const isEmpty = isLoaded && alerts.length === 0;
    const sorted = isLoaded ? orderBy(alerts, [orderByField], [orderByReverse ? "desc" : "asc"]) : [];
    const pageItems = sorted.slice((page - 1) * itemsPerPage, page * itemsPerPage);

    return (
      <div className="page-alerts-list">
        <div className="container">
          <PageHeader
            title="Alerts"
            actions={
              currentUser.hasPermission("list_alerts") ? (
                <Link.Button block type="primary" href="alerts/new">
                  <i className="fa fa-plus m-r-5" aria-hidden="true" />
                  New Alert
                </Link.Button>
              ) : null
            }
          />
          <div>
            {isLoaded && isEmpty ? (
              <DynamicComponent name="AlertsList.EmptyState">
                <EmptyState
                  icon="fa fa-bell-o"
                  illustration="alert"
                  description="Get notified on certain events"
                  helpMessage={<EmptyStateHelpMessage helpTriggerType="ALERTS" />}
                  showAlertStep
                />
              </DynamicComponent>
            ) : (
              <div className="table-responsive bg-white tiled">
                <ItemsTable
                  loading={!isLoaded}
                  items={pageItems}
                  columns={this.listColumns}
                  orderByField={orderByField}
                  orderByReverse={orderByReverse}
                  toggleSorting={this.toggleSorting}
                />
                <Paginator
                  showPageSizeSelect
                  totalCount={sorted.length}
                  pageSize={itemsPerPage}
                  onPageSizeChange={(size) => this.setState({ itemsPerPage: size, page: 1 })}
                  page={page}
                  onChange={(next) => this.setState({ page: next })}
                />
              </div>
            )}
          </div>
        </div>
      </div>
    );
  }
}

routes.register(
  "Alerts.List",
  routeWithUserSession({
    path: "/alerts",
    title: "Alerts",
    render: (pageProps) => <AlertsList {...pageProps} currentPage="alerts" />,
  })
);
