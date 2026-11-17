import { RiBarChartLine } from "@remixicon/react";
import { useNavigate, useRouterState } from "@tanstack/react-router";
import { DashboardSidebar } from "@/components/application/dashboard/dashboard-sidebar";
import { DashboardUserMenu } from "@/components/application/dashboard/dashboard-user-menu";
import { AccountMenu } from "@/modules/auth/AccountMenu";

const navigation = [
	{
		key: "/analytics",
		label: "Analytics",
		icon: RiBarChartLine,
		href: "/analytics",
	},
];

export function Sidebar() {
	const navigate = useNavigate();
	const pathname = useRouterState({
		select: (state) => state.location.pathname,
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
			settingsContent={
				<p className="py-4 text-body-regular text-text-secondary">
					Account settings are not available yet. Appearance can be changed in
					the sidebar.
				</p>
			}
		/>
	);
}
