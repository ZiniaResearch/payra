import {
	RiBarChartLine,
	RiCalendarScheduleLine,
	RiCoinLine,
	RiMessage2Line,
} from "@remixicon/react";
import { useNavigate, useRouterState } from "@tanstack/react-router";
import { DashboardSidebar } from "@/components/application/dashboard/dashboard-sidebar";
import { DashboardUserMenu } from "@/components/application/dashboard/dashboard-user-menu";
import { AccountMenu } from "@/modules/auth/AccountMenu";

const navigation = [
	{
		key: "/messages/new",
		label: "Schedule SMS",
		icon: RiCalendarScheduleLine,
		href: "/messages/new",
	},
	{
		key: "/messages",
		label: "Messages",
		icon: RiMessage2Line,
		href: "/messages",
	},
	{
		key: "/analytics",
		label: "Analytics",
		icon: RiBarChartLine,
		href: "/analytics",
	},
	{
		key: "/billing",
		label: "Buy Credits",
		icon: RiCoinLine,
		href: "/billing",
	},
];

export function Sidebar() {
	const navigate = useNavigate();
	const pathname = useRouterState({
		select: (state) => state.location.pathname.replace(/\/$/, ""),
	});

	return (
		<DashboardSidebar
			className="sticky top-3 h-[calc(100dvh-24px)]"
			items={navigation}
			selected={pathname}
			onNavigate={(href) => void navigate({ to: href })}
			renderUserMenu={(props) => (
				<DashboardUserMenu {...props} name="Payra" initials="P">
					<p className="p-3 text-body-regular text-text-secondary">
						Workspace management is not available yet.
					</p>
				</DashboardUserMenu>
			)}
			renderTeamMenu={(props) => <AccountMenu {...props} />}
		/>
	);
}
