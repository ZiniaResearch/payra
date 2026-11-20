import { RiLogoutBoxRLine, RiShieldUserLine } from "@remixicon/react";
import { useQuery } from "@tanstack/react-query";
import { type ComponentProps, useState } from "react";

import { DashboardTeamMenu } from "@/components/application/dashboard/dashboard-team-menu";
import { Avatar } from "@/components/base/avatar/avatar";
import { Button } from "@/components/base/buttons/button";
import { Divider } from "@/components/base/divider/divider";
import { Skeleton, SkeletonText } from "@/components/base/skeleton/skeleton";
import { ProfileModal } from "@/modules/profile/ProfileModal";
import { currentAccountQueryOptions } from "@/modules/profile/queries";
import { useLogout } from "./use-logout";

export function AccountMenu({
	collapsed,
	className,
}: Pick<ComponentProps<typeof DashboardTeamMenu>, "collapsed" | "className">) {
	const account = useQuery(currentAccountQueryOptions());
	const { logout, pending, error } = useLogout();
	const [menuOpen, setMenuOpen] = useState(false);
	const [profileOpen, setProfileOpen] = useState(false);

	const name = account.data
		? account.data.name
		: account.isPending
			? "Loading account"
			: "Account unavailable";

	const initials =
		Array.from(account.data?.name.trim() ?? "")[0]?.toLocaleUpperCase() ?? "?";

	const avatar = account.isPending ? (
		<Skeleton shape="circle" className="size-8 shrink-0" />
	) : (
		<Avatar
			size="md"
			src={account.data?.avatar_url}
			alt={`${name}'s profile image`}
			initials={initials}
		/>
	);

	return (
		<>
			<DashboardTeamMenu
				isOpen={menuOpen}
				onOpenChange={setMenuOpen}
				collapsed={collapsed}
				className={className}
				name={name}
				email={account.data?.email ?? "Open menu to retry"}
				avatar={avatar}
				summary={
					account.isPending ? (
						<output
							aria-busy="true"
							aria-label="Loading account information"
							className="w-32"
						>
							<SkeletonText lines={2} lineClassName="h-3" />
						</output>
					) : undefined
				}
				footer={null}
			>
				<div className="flex flex-col gap-3 p-2">
					{account.isError && (
						<div className="flex flex-col items-start gap-2">
							<p
								role="alert"
								className="text-body-regular text-text-error-primary"
							>
								Could not load account information.
							</p>
							<Button
								variant="secondary"
								size="small"
								disabled={account.isFetching}
								onClick={() => void account.refetch()}
							>
								{account.isFetching ? "Retrying…" : "Try again"}
							</Button>
						</div>
					)}
					<Button
						variant="ghost"
						className="justify-start bg-transparent text-text-primary hover:bg-background-primary-hover active:bg-background-primary-active"
						leadingIcon={RiShieldUserLine}
						onClick={() => {
							setMenuOpen(false);
							setProfileOpen(true);
						}}
					>
						Account Details
					</Button>
					<Divider className="my-1" />
					<Button
						variant="danger"
						className="justify-start"
						leadingIcon={RiLogoutBoxRLine}
						disabled={pending}
						onClick={() => void logout()}
					>
						{pending ? "Signing out…" : "Sign out"}
					</Button>
					{error && (
						<p
							role="alert"
							className="text-body-regular text-text-error-primary"
						>
							{error}
						</p>
					)}
				</div>
			</DashboardTeamMenu>
			<ProfileModal
				key={account.data?.account_id ?? "profile"}
				isOpen={profileOpen}
				onOpenChange={setProfileOpen}
				account={account.data}
				isLoading={account.isPending}
			/>
		</>
	);
}
