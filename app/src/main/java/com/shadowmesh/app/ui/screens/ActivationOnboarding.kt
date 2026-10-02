package com.shadowmesh.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shadowmesh.ui_kit.theme.LocalShadowMeshColors

/**
 * Pre-activation explainer, shown before the access-code entry.
 *
 * ## Why this content is compiled in and not fetched
 *
 * The obvious design for an explainer like this is to pull the copy from a
 * server. For this app that is exactly backwards. The copy is the *sales pitch
 * for a censorship-resistance tool*, and a user who cannot reach our servers is
 * precisely the user who most needs the app to explain itself. Anything fetched
 * before activation would also mean making a network request -- and surfacing
 * the request -- before the user has any protection at all, which leaks the
 * fact that this app was opened to anyone watching the network.
 *
 * So the copy ships in the binary, is identical offline, and the first network
 * call the app ever makes is the authenticated one.
 *
 * ## Why it looks the way it does
 *
 * Every step is static geometry: no `Modifier.blur`, no `graphicsLayer`, no
 * infinite transitions. The screens this replaced animated a 700.dp box through
 * a 120.dp gaussian blur on a layer whose scale and alpha never settled, and
 * that alone accounted for a large share of the process's graphics memory.
 * An onboarding flow is the last place that budget can be spent.
 */
enum class OnboardingStep {
    WHY,
    HOW,
    PRIVACY,
    ;

    companion object {
        /** The step index at which the real access-code entry takes over. */
        const val ACTIVATION_STEP = 3

        val ordered: List<OnboardingStep> = entries
    }
}

private data class OnboardingPage(
    val eyebrow: String,
    val title: String,
    val body: String,
    val points: List<String>,
)

private fun pageFor(step: OnboardingStep): OnboardingPage = when (step) {
    OnboardingStep.WHY -> OnboardingPage(
        eyebrow = "WHY",
        title = "The network is not neutral",
        body = "Some networks are told what their users may reach. Blocking is " +
            "usually crude on purpose, and shallow probes are easy to detect.",
        points = listOf(
            "Encryption alone does not help if the connection is refused.",
            "A disguised handshake blurs into ordinary traffic.",
            "Failing quietly beats failing loudly.",
        ),
    )

    OnboardingStep.HOW -> OnboardingPage(
        eyebrow = "HOW",
        title = "One switch, real transports",
        body = "ShadowMesh routes your traffic over a small set of " +
            "independent transports, and moves to another when one is degraded.",
        points = listOf(
            "WireGuard and REALITY are what run today.",
            "More transports are landing, and the app will not pretend otherwise.",
            "Split tunnelling keeps chosen apps off the tunnel.",
        ),
    )

    OnboardingStep.PRIVACY -> OnboardingPage(
        eyebrow = "PRIVACY",
        title = "No account, no trail",
        body = "Activation is a one-time code tied to your device. There is no " +
            "email address, no phone number, and no advertising identifier.",
        points = listOf(
            "Your activation code lives only on this device.",
            "We cannot tell who you are, because you never told us.",
            "Revoke access by removing the device, not by writing to us.",
        ),
    )
}

@Composable
fun ActivationOnboarding(
    stepIndex: Int,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val smColors = LocalShadowMeshColors.current
    val step = OnboardingStep.ordered[stepIndex.coerceIn(0, OnboardingStep.ordered.lastIndex)]
    val page = pageFor(step)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(smColors.cyberObsidianStart)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp)
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OnboardingStep.ordered.indices.forEach { index ->
                    val active = index <= stepIndex
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(3.dp)
                            .background(
                                color = if (active) {
                                    smColors.statusReady
                                } else {
                                    Color.White.copy(alpha = 0.12f)
                                },
                                shape = RoundedCornerShape(2.dp),
                            )
                            .semantics {
                                contentDescription = "Step ${index + 1} of ${OnboardingStep.ordered.size}"
                            }
                    )
                }
            }

            Spacer(modifier = Modifier.height(72.dp))

            AegisMark(
                themeColor = smColors.statusReady,
                modifier = Modifier.size(96.dp),
            )

            Spacer(modifier = Modifier.height(40.dp))

            Text(
                text = page.eyebrow,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 5.sp,
                    color = smColors.statusReady,
                ),
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = page.title,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                ),
            )

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = page.body,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = Color.White.copy(alpha = 0.62f),
                    lineHeight = 22.sp,
                ),
            )

            Spacer(modifier = Modifier.height(36.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                page.points.forEach { point ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            modifier = Modifier
                                .padding(top = 7.dp)
                                .size(6.dp)
                                .background(smColors.statusReady, CircleShape)
                        )
                        Spacer(modifier = Modifier.size(14.dp))
                        Text(
                            text = point,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = Color.White.copy(alpha = 0.78f),
                                lineHeight = 20.sp,
                            ),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Surface(
                onClick = onNext,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color.White.copy(alpha = 0.06f),
            ) {
                Text(
                    text = "Continue",
                    modifier = Modifier.padding(vertical = 16.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        color = Color.White,
                    ),
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Surface(
                    onClick = onBack,
                    enabled = stepIndex > 0,
                    color = Color.Transparent,
                ) {
                    Text(
                        text = "Back",
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = Color.White.copy(
                                alpha = if (stepIndex > 0) 0.6f else 0.2f
                            ),
                        ),
                    )
                }
                Surface(
                    onClick = onSkip,
                    color = Color.Transparent,
                ) {
                    Text(
                        text = "Skip",
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = Color.White.copy(alpha = 0.6f),
                        ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
