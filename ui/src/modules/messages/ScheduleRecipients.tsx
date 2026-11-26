import { RiDeleteBinLine, RiPencilLine, RiSearchLine } from "@remixicon/react";
import {
	type KeyboardEvent,
	type ReactElement,
	useId,
	useRef,
	useState,
} from "react";
import {
	GridList,
	GridListItem,
	Group,
	Input,
	type Key,
	Pressable,
	type Selection,
} from "react-aria-components";
import { Avatar } from "@/components/base/avatar/avatar";
import { CHIP_COLORS, Chip } from "@/components/base/badges/chip";
import { Button } from "@/components/base/buttons/button";
import { CloseButton } from "@/components/base/buttons/close-button";
import {
	inputElementClassName,
	inputFieldClassName,
	TextField,
} from "@/components/base/input/input";
import { Label } from "@/components/base/input/label";
import { Pagination } from "@/components/base/pagination/pagination";
import { ScheduleRecipientEditor } from "@/modules/messages/ScheduleRecipientEditor";
import { cx } from "@/utils/cx";

type Recipient = {
	id: Key;
	phone: string;
	name?: string;
};

const sampleRecipients: Recipient[] = [
	{ name: "Amina Rahman", phone: "01728361492" },
	{ name: "Rafi Ahmed", phone: "01856920374" },
	{ phone: "01972480631" },
	{ name: "Nadia Islam", phone: "01317842965" },
	{ name: "Tanvir Hasan", phone: "01682530417" },
	{ phone: "01406739258" },
	{ name: "Farhana Karim", phone: "01521764839" },
	{ phone: "01753908624" },
	{ name: "Imran Hossain", phone: "01933875146" },
	{ name: "Samira Khan", phone: "01874609283" },
	{ phone: "01309625714" },
	{ name: "Arif Chowdhury", phone: "01641735982" },
	{ name: "Nusrat Jahan", phone: "01402568193" },
	{ phone: "01549380627" },
	{ name: "Shafin Alam", phone: "01790462835" },
	{ phone: "01829154763" },
	{ name: "Maliha Sultana", phone: "01960531748" },
	{ phone: "01323576904" },
].map((recipient, id) => ({ ...recipient, id }));

const RECIPIENTS_PER_PAGE = 10;
const ADD_RECIPIENT_KEY = "add-recipient";
const COMPLETE_PHONE_NUMBER = /^\+?\d{7,15}$/;
const RECIPIENT_ACCENTS = [
	{
		tone: "blue",
		nameClassName: "text-status-blue-text",
		chipClassName: "bg-status-blue-background/75",
	},
	{
		tone: "purple",
		nameClassName: "text-status-purple-text",
		chipClassName: "bg-status-purple-background/75",
	},
	{
		tone: "cyan",
		nameClassName: "text-status-cyan-text",
		chipClassName: "bg-status-cyan-background/75",
	},
	{
		tone: "lime",
		nameClassName: "text-status-lime-text",
		chipClassName: "bg-status-lime-background/75",
	},
	{
		tone: "rose",
		nameClassName: "text-status-rose-text",
		chipClassName: "bg-status-rose-background/75",
	},
] as const;

function getRecipientAccent(id: Key) {
	if (typeof id === "number") {
		return RECIPIENT_ACCENTS[id % RECIPIENT_ACCENTS.length];
	}
}

function normalizePhoneQuery(value: string) {
	const query = value.trim();
	if (!/^\+?[\d\s().-]+$/.test(query)) return "";
	return query.replace(/[\s().-]/g, "");
}

interface RecipientRowProps {
	recipient: Recipient;
	editor?: ReactElement;
	onEdit: () => void;
	onDelete: () => void;
}

function RecipientRow({
	recipient,
	editor,
	onEdit,
	onDelete,
}: RecipientRowProps) {
	const { id, name, phone } = recipient;
	const isNew = id === ADD_RECIPIENT_KEY;
	const label = name ?? phone;
	const accent = getRecipientAccent(id);
	const initials = name
		?.split(/\s+/, 2)
		.map((word) => word[0])
		.join("")
		.toLocaleUpperCase();

	return (
		<GridListItem
			id={id}
			focusMode={editor ? "child" : "row"}
			textValue={name ? `${name} ${phone}` : label}
			className={({ isSelected }) =>
				cx(
					"grid min-w-0 content-center rounded-lg px-2 py-1.5 outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-border-focus-ring",
					!editor && "cursor-pointer",
					isSelected
						? "bg-background-tertiary-hover/60 hover:bg-background-tertiary-hover/80"
						: "hover:bg-background-tertiary-hover/30",
				)
			}
		>
			{editor ?? (
				<div className="flex min-h-8 items-center gap-2">
					<Avatar
						size="md"
						initials={initials ?? "#"}
						className={accent && CHIP_COLORS[accent.tone]}
						aria-hidden
					/>
					<div className="flex min-w-0 flex-1 flex-col text-body-medium">
						{name && (
							<bdi className={cx("truncate", accent?.nameClassName)}>
								{name}
							</bdi>
						)}
						<span
							className={cx(
								"truncate",
								name && "text-body-2-regular text-text-secondary",
							)}
						>
							{isNew && "Add "}
							<bdi dir="ltr">{phone}</bdi>
						</span>
					</div>
					{!isNew && (
						<div className="flex shrink-0 items-center gap-1">
							<Pressable onPress={onEdit}>
								<Button
									variant="ghost"
									size="small"
									iconOnly
									leadingIcon={RiPencilLine}
									aria-label={`Edit ${label}`}
									className="bg-transparent text-text-primary hover:bg-transparent hover:text-status-blue-text focus-visible:text-status-blue-text active:bg-transparent"
								/>
							</Pressable>
							<Pressable onPress={onDelete}>
								<Button
									variant="ghost"
									size="small"
									iconOnly
									leadingIcon={RiDeleteBinLine}
									aria-label={`Delete ${label}`}
									className="bg-transparent text-text-primary hover:bg-transparent hover:text-status-rose-text focus-visible:text-status-rose-text active:bg-transparent"
								/>
							</Pressable>
						</div>
					)}
				</div>
			)}
		</GridListItem>
	);
}

export function ScheduleRecipients() {
	const [recipients, setRecipients] = useState(sampleRecipients);
	const [selectedRecipients, setSelectedRecipients] = useState(
		() => new Set<Key>(),
	);
	const [editingId, setEditingId] = useState<Key | null>(null);
	const [query, setQuery] = useState("");
	const [requestedPage, setPage] = useState(1);
	const nextRecipientId = useRef(sampleRecipients.length);
	const inputRef = useRef<HTMLInputElement>(null);
	const listRef = useRef<HTMLDivElement>(null);
	const listId = useId();
	const phoneQuery = normalizePhoneQuery(query);
	const nameQuery = query.trim().toLocaleLowerCase();
	const matches = recipients.filter(({ phone, name }) =>
		phoneQuery
			? phone.includes(phoneQuery)
			: !nameQuery || name?.toLocaleLowerCase().includes(nameQuery),
	);

	const isNewPhone =
		COMPLETE_PHONE_NUMBER.test(phoneQuery) &&
		!matches.some(({ phone }) => phone === phoneQuery);
	if (isNewPhone) {
		matches.push({ id: ADD_RECIPIENT_KEY, phone: phoneQuery });
	}

	const totalPages = Math.ceil(matches.length / RECIPIENTS_PER_PAGE);
	const page = Math.min(requestedPage, Math.max(totalPages, 1));
	const pageStart = (page - 1) * RECIPIENTS_PER_PAGE;
	const pageRecipients = matches.slice(
		pageStart,
		pageStart + RECIPIENTS_PER_PAGE,
	);

	function changeQuery(value: string) {
		setQuery(value);
		changePage(1);
	}

	function changePage(nextPage: number) {
		setPage(nextPage);
		setEditingId(null);
	}

	function openNewRecipient() {
		setPage(totalPages);
		setEditingId(ADD_RECIPIENT_KEY);
	}

	function resetSearchAndFocus() {
		changeQuery("");
		inputRef.current?.focus();
	}

	function changeSelection(selection: Selection) {
		if (selection === "all") return;
		if (selection.has(ADD_RECIPIENT_KEY)) {
			if (isNewPhone) openNewRecipient();
			return;
		}
		setSelectedRecipients(selection);
		if (query && selection.size > selectedRecipients.size) {
			resetSearchAndFocus();
		}
	}

	function removeRecipient(id: Key) {
		setSelectedRecipients((current) => {
			if (!current.has(id)) return current;
			const next = new Set(current);
			next.delete(id);
			return next;
		});
		inputRef.current?.focus();
	}

	function clearRecipients() {
		setSelectedRecipients(new Set());
		inputRef.current?.focus();
	}

	function closeEditor() {
		setEditingId(null);
		inputRef.current?.focus();
	}

	function saveRecipient(id: Key, name: string, phone: string) {
		const normalizedPhone = normalizePhoneQuery(phone);
		if (!COMPLETE_PHONE_NUMBER.test(normalizedPhone))
			return "Enter a valid phone number.";
		if (
			recipients.some(
				(recipient) =>
					recipient.id !== id && recipient.phone === normalizedPhone,
			)
		) {
			return "This number is already saved.";
		}

		const isNew = id === ADD_RECIPIENT_KEY;
		const saved = {
			id: isNew ? nextRecipientId.current++ : id,
			name: name.trim() || undefined,
			phone: normalizedPhone,
		};

		if (isNew) {
			setRecipients((current) => [...current, saved]);
			setSelectedRecipients((current) => new Set(current).add(saved.id));
			resetSearchAndFocus();
			return;
		}
		setRecipients((current) =>
			current.map((recipient) => (recipient.id === id ? saved : recipient)),
		);
		closeEditor();
	}

	function deleteRecipient(id: Key) {
		setRecipients((current) =>
			current.filter((recipient) => recipient.id !== id),
		);
		removeRecipient(id);
	}

	function handleSearchKeyDown(event: KeyboardEvent<HTMLInputElement>) {
		if (event.key === "Enter" && isNewPhone && !event.nativeEvent.isComposing) {
			event.preventDefault();
			openNewRecipient();
			return;
		}
		if (event.key !== "ArrowDown" && event.key !== "ArrowUp") return;
		const rows = listRef.current?.querySelectorAll<HTMLElement>(
			"[role=row][aria-selected]",
		);
		const row = event.key === "ArrowDown" ? rows?.[0] : rows?.[rows.length - 1];
		if (!row) return;
		event.preventDefault();
		row.focus();
	}

	const recipientsById = new Map(
		recipients.map((recipient) => [recipient.id, recipient]),
	);

	return (
		<div className="flex flex-col gap-2">
			<TextField value={query} onChange={changeQuery}>
				<Label>Recipients</Label>
				<Group
					className={(state) =>
						cx(inputFieldClassName(state), "flex-col items-stretch gap-1")
					}
				>
					{selectedRecipients.size > 0 && (
						<div className="flex min-w-0 flex-wrap items-center gap-1">
							{[...selectedRecipients].map((id) => {
								const recipient = recipientsById.get(id);
								if (!recipient) return null;
								const label = recipient.name ?? recipient.phone;
								const accent = getRecipientAccent(id);
								return (
									<Chip
										key={id}
										color={accent?.tone}
										variant="caption"
										className={cx(
											"min-h-8.5 max-w-full px-1 py-px whitespace-normal",
											accent?.chipClassName,
											recipient.name ? "gap-1.5" : "gap-0.5",
										)}
									>
										<span className="flex min-w-0 flex-col items-start wrap-anywhere">
											<bdi dir={recipient.name ? undefined : "ltr"}>
												{label}
											</bdi>
											{recipient.name && (
												<bdi dir="ltr" className="text-caption-2-regular">
													{recipient.phone}
												</bdi>
											)}
										</span>
										<CloseButton
											size="sm"
											aria-label={`Remove ${label}`}
											className="rounded-md bg-transparent text-current hover:bg-current/10 hover:text-current focus-visible:bg-current/10"
											onClick={() => removeRecipient(id)}
										/>
									</Chip>
								);
							})}
							<Button
								variant="secondary"
								size="xs"
								className="h-8.5 rounded-md px-1.5"
								onClick={clearRecipients}
							>
								Clear All
							</Button>
						</div>
					)}
					<div className="flex min-w-0 items-center gap-1.5">
						<RiSearchLine className="size-5 shrink-0" aria-hidden />
						<Input
							ref={inputRef}
							aria-controls={listId}
							placeholder={
								selectedRecipients.size
									? "Search or add a number"
									: "Search by name or phone number"
							}
							className={cx(inputElementClassName, "h-8 ps-1 text-start")}
							onKeyDown={handleSearchKeyDown}
						/>
					</div>
				</Group>
			</TextField>
			<GridList
				ref={listRef}
				id={listId}
				aria-label="Saved recipients"
				selectionMode="multiple"
				keyboardNavigationBehavior={editingId === null ? "arrow" : "tab"}
				shouldSelectOnPressUp
				disabledBehavior="selection"
				disabledKeys={editingId === null ? [] : [editingId]}
				selectedKeys={selectedRecipients}
				onSelectionChange={changeSelection}
				className="grid gap-1 rounded-xl border border-border-button-default bg-background-primary-default p-1 outline-none"
				style={{
					gridTemplateRows: `repeat(${RECIPIENTS_PER_PAGE}, minmax(calc(var(--spacing) * 13), max-content))`,
				}}
				renderEmptyState={() => (
					<p className="p-2 text-body-2-regular text-text-secondary">
						No matching recipients
					</p>
				)}
			>
				{pageRecipients.map((recipient) => (
					<RecipientRow
						key={recipient.id}
						recipient={recipient}
						editor={
							recipient.id === editingId ? (
								<ScheduleRecipientEditor
									mode={recipient.id === ADD_RECIPIENT_KEY ? "add" : "edit"}
									name={recipient.name}
									phone={recipient.phone}
									onSave={(name, phone) =>
										saveRecipient(recipient.id, name, phone)
									}
									onCancel={closeEditor}
								/>
							) : undefined
						}
						onEdit={() => setEditingId(recipient.id)}
						onDelete={() => deleteRecipient(recipient.id)}
					/>
				))}
			</GridList>
			<Pagination
				aria-label="Recipient pages"
				page={page}
				totalPages={totalPages}
				onChange={changePage}
			/>
		</div>
	);
}
