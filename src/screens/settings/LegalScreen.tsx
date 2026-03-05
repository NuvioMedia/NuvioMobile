import React from 'react';
import { View, Text, StyleSheet, ScrollView, StatusBar } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import { useTranslation } from 'react-i18next';
import { useTheme } from '../../contexts/ThemeContext';
import ScreenHeader from '../../components/common/ScreenHeader';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { RootStackParamList } from '../../navigation/AppNavigator';
import { NavigationProp } from '@react-navigation/native';

const LegalScreen: React.FC = () => {
	const { t } = useTranslation();
	const { currentTheme } = useTheme();
	const navigation = useNavigation<NavigationProp<RootStackParamList>>();
	const insets = useSafeAreaInsets();

	const sections = [
		{
			title: t('legal.intro_title'),
			text: t('legal.intro_text'),
		},
		{
			title: t('legal.extensions_title'),
			text: t('legal.extensions_text'),
		},
		{
			title: t('legal.user_resp_title'),
			text: t('legal.user_resp_text'),
		},
		{
			title: t('legal.dmca_title'),
			text: t('legal.dmca_text'),
		},
		{
			title: t('legal.warranty_title'),
			text: t('legal.warranty_text'),
		},
	];

	return (
		<View
			style={[
				styles.container,
				{ backgroundColor: currentTheme.colors.darkBackground },
			]}
		>
			<StatusBar barStyle="light-content" />
			<ScreenHeader
				title={t('legal.title')}
				showBackButton
				onBackPress={() => navigation.goBack()}
			/>

			<ScrollView
				style={styles.scrollView}
				contentContainerStyle={[
					styles.contentContainer,
					{ paddingBottom: insets.bottom + 40 },
				]}
				showsVerticalScrollIndicator={false}
			>
				{sections.map((section, index) => (
					<View key={index} style={styles.section}>
						<Text
							style={[
								styles.sectionTitle,
								{ color: currentTheme.colors.highEmphasis },
							]}
						>
							{section.title}
						</Text>
						<Text
							style={[
								styles.sectionText,
								{ color: currentTheme.colors.mediumEmphasis },
							]}
						>
							{section.text}
						</Text>
					</View>
				))}

				<View style={styles.footer}>
					<Text style={[styles.footerText, { color: currentTheme.colors.disabled }]}>
						{t('legal.last_updated')} January 2026
					</Text>
				</View>
			</ScrollView>
		</View>
	);
};

const styles = StyleSheet.create({
	container: {
		flex: 1,
	},
	scrollView: {
		flex: 1,
	},
	contentContainer: {
		padding: 24,
		gap: 32,
	},
	section: {
		gap: 12,
	},
	sectionTitle: {
		fontSize: 20,
		fontWeight: '700',
		letterSpacing: 0.3,
	},
	sectionText: {
		fontSize: 16,
		lineHeight: 26,
	},
	footer: {
		alignItems: 'center',
		marginTop: 16,
		paddingVertical: 20,
		borderTopWidth: 1,
		borderTopColor: 'rgba(255,255,255,0.1)',
	},
	footerText: {
		fontSize: 13,
	},
});

export default LegalScreen;
